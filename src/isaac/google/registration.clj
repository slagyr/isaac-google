(ns isaac.google.registration
  "Berth :isaac.google/registration and the reconcile timer.

   A reconcile pass is per organization: subscriptions live in a tenant's own
   GCP project, are created with that tenant's token, and expire on that
   tenant's window. So one tick surveys and reconciles each configured tenant
   in turn with `tenants/*tenant*` bound; a single-organization host has one
   tenant (`:default`) and behaves exactly as before (isaac-1zkz)."
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.set :as set]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.google.events :as events]
    [isaac.google.health :as health]
    [isaac.google.heartbeat :as heartbeat]
    [isaac.google.tenants :as tenants]
    [isaac.logger :as log]
    [isaac.module.berths :as berths]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]
    [isaac.runner :as runner]
    [isaac.tool.memory :as memory])
  (:import (java.time Duration Instant)))

(defonce ^:private registrations* (atom {}))

(def DEFAULT-RENEW-HOURS 24)

(defn reset-registrations! []
  (reset! registrations* {}))

(defn- resolve-sym [x]
  (cond
    (fn? x) x
    (var? x) @x
    (symbol? x) (some-> (requiring-resolve x) deref)
    :else x))

(defn register!
  "Per-entry factory. Entry is [id {:create! :renew! :expiry :key}] plus two
   optional hooks for registrations Google cannot list: :remote (fn [] ->
   {key {:name :expires-at}}, defaults to the Workspace Events listing) and
   :delete! (fn [name], defaults to deleting the Workspace Events subscription)."
  [[id entry]]
  (swap! registrations* assoc id
         (-> entry
             (update :create! resolve-sym)
             (update :renew! resolve-sym)
             (update :expiry resolve-sym)
             (update :key resolve-sym)
             (update :remote resolve-sym)
             (update :delete! resolve-sym)))
  id)

(defn all []
  @registrations*)

(defn- ensure-contributions!
  "Register any :isaac.google/registration contributions not yet in the atom
   (feature runs skip full berth processing)."
  []
  (doseq [[_ contribution] (berths/contributions-to-berth (discovery/builtin-index) :isaac.google/registration)]
    (when (map? contribution)
      (doseq [entry contribution]
        (when-not (contains? @registrations* (key entry))
          (register! entry))))))

(defn- ->instant [v]
  (cond
    (instance? Instant v) v
    (string? v) (Instant/parse v)
    :else v))

(defn within-window?
  "True when `expires-at` is at or before now + `hours` (including already expired)."
  [now expires-at hours]
  (let [now  (->instant now)
        exp  (->instant expires-at)
        hrs  (or hours DEFAULT-RENEW-HOURS)]
    (and now exp (not (.isAfter exp (.plus now (Duration/ofHours hrs)))))))

(defn plan
  "Configured keys × Google remote state → create/renew/delete/noop."
  [{:keys [configured remote now renew-within-hours]}]
  (let [configured  (set configured)
        remote-keys (set (keys remote))
        hours       (or renew-within-hours DEFAULT-RENEW-HOURS)]
    (vec
      (concat
        (for [k (sort configured)]
          (if-let [sub (get remote k)]
            (if (within-window? now (:expires-at sub) hours)
              {:op :renew :key k :name (:name sub)}
              {:op :noop :key k :name (:name sub)})
            {:op :create :key k}))
        (for [k (sort (set/difference remote-keys configured))]
          {:op :delete :key k :name (:name (get remote k))})))))

(defn- runtime-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn- state-path [root]
  (str root "/google/registrations.edn"))

(defn load-state [root]
  (let [fs*  (runtime-fs)
        path (state-path root)]
    (if (fs/exists? fs* path)
      (edn/read-string (fs/slurp fs* path))
      {})))

(defn save-state! [root state]
  (let [fs*  (runtime-fs)
        path (state-path root)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (write-edn state))
    state))

(defn- feature-root []
  (or (nexus/get :root) (root/current-root)))

(defn- load-cfg []
  (let [root (feature-root)
        snap (loader/snapshot "google registration")
        cfg  (if (some (comp :topic val) (tenants/tenants snap))
               snap
               (or (when root
                     (:config (loader/load-config-result {:root root :fs (runtime-fs)})))
                   snap
                   {}))]
    cfg))

(defn- renew-hours
  "How close to expiry this organization renews. Each sets its own."
  [cfg id]
  (let [v (:renew-within-hours (tenants/tenant-config cfg id))]
    (cond
      (int? v) v
      (number? v) (long v)
      (string? v) (or (parse-long v) DEFAULT-RENEW-HOURS)
      :else DEFAULT-RENEW-HOURS)))

(defn- call-keys [entry]
  (let [f (:key entry)]
    (cond
      (fn? f) (or (f) [])
      (sequential? f) f
      :else [])))

(defn- remote-key [sub]
  (let [target (str (or (:targetResource sub) ""))]
    (or (second (re-find #"googleapis\.com/(.*)$" target))
        (:key sub))))

(defn- remote-index [subscriptions expiry-fn]
  (into {}
        (keep (fn [sub]
                (when-let [k (remote-key sub)]
                  [k {:name       (:name sub)
                      :expires-at (or (when expiry-fn (expiry-fn sub))
                                      (:expireTime sub)
                                      (:expires-at sub))
                      :raw        sub}]))
              subscriptions)))

(defn- expires-at [result expiry-fn]
  (or (when expiry-fn (expiry-fn result))
      (:expireTime result)
      (:expires-at result)))

(defn- failed? [result]
  (or (:error result)
      (and (:status result) (>= (:status result) 400))))

(defn- fail-reason [result]
  (or (:message result)
      (get-in result [:body :error :message])
      (get-in result [:body :error])
      (when (:status result) (str (:status result)))
      "registration failed"))

(defn- iso [instant]
  (when instant
    (str (->instant instant))))

(defn- remember! [root key name expires]
  (let [state (assoc (load-state root) key {:name name :expires-at (iso expires)})]
    (save-state! root state)))

(defn- forget! [root key]
  (save-state! root (dissoc (load-state root) key)))

(defn- execute! [root entry action]
  (let [create!   (:create! entry)
        renew!    (:renew! entry)
        expiry-fn (:expiry entry)]
    (case (:op action)
      :create
      (let [result (create! (:key action))]
        (if (failed? result)
          (log/error :google/registration-failed :key (:key action) :reason (fail-reason result))
          (do
            (remember! root (:key action) (:name result) (expires-at result expiry-fn))
            (log/info :google/registered :key (:key action) :expires-at (iso (expires-at result expiry-fn))))))

      :renew
      (let [result (renew! (:name action))]
        (if (failed? result)
          (log/error :google/registration-failed :key (:key action) :reason (fail-reason result))
          (do
            (remember! root (:key action) (or (:name result) (:name action)) (expires-at result expiry-fn))
            (log/info :google/renewed :key (:key action) :expires-at (iso (expires-at result expiry-fn))))))

      :delete
      (do
        (if-let [delete! (:delete! entry)]
          (delete! (:name action))
          (events/delete-subscription! (:name action)))
        (forget! root (:key action))
        (log/info :google/unregistered :key (:key action)))

      :noop
      nil)))

(defn- door-up? []
  (boolean
    (try
      (or (runner/running?)
          (some? ((requiring-resolve 'isaac.component.registry/instance-for) :http))
          (some? ((requiring-resolve 'isaac.component.registry/instance-for) :server-runtime))
          (some? ((requiring-resolve 'isaac.component.registry/instance-for) :google-registration)))
      (catch Exception _ false))))

(defn- list-subscriptions []
  (try
    (or (:subscriptions (events/list-subscriptions!)) [])
    (catch Exception _ [])))

(defn- remote-for [entry listed]
  (if-let [remote (:remote entry)]
    (or (remote) {})
    (remote-index (or listed []) (:expiry entry))))

(defn- survey-tenant
  "What one organization has and what it needs, without changing anything.
   Listing and every :key / :remote hook run as that tenant, so they reach
   its Google project with its token."
  [now cfg entries id]
  (binding [tenants/*tenant* id]
    (let [hours  (renew-hours cfg id)
          listed (list-subscriptions)
          plans  (mapv (fn [[_ entry]]
                         (let [configured (call-keys entry)
                               remote     (remote-for entry listed)]
                           {:entry   entry
                            :keys    configured
                            :remote  remote
                            :actions (plan {:configured         configured
                                            :remote             remote
                                            :now                now
                                            :renew-within-hours hours})}))
                       entries)]
      {:tenant id
       :keys   (vec (mapcat :keys plans))
       :remote (if (seq plans)
                 (apply merge (map :remote plans))
                 (remote-index listed nil))
       :plans  plans})))

(defn- execute-tenant! [root {:keys [tenant plans]}]
  (binding [tenants/*tenant* tenant]
    (doseq [{:keys [entry actions]} plans
            action                  actions]
      (try
        (execute! root entry action)
        (catch Exception e
          (log/error :google/registration-failed
                     :key (:key action)
                     :reason (.getMessage e)))))))

(defn- health-tenants
  "Each organization with every key health should judge it by: the keys it
   registers, plus every key the health state has heard from. The two are not
   the same — one registration can cover a whole workspace (`spaces/-`) while
   events arrive under concrete space ids, so judging registration keys alone
   would be blind. Only a host with a single organization can say whose the
   seen keys are (isaac-an14)."
  [surveys state solo?]
  (let [seen (vec (sort (keys (or (:last-event-at state) {}))))]
    (mapv (fn [survey]
            {:tenant (:tenant survey)
             :keys   (vec (distinct (concat (:keys survey) (when solo? seen))))})
          surveys)))

(defn tick!
  "One reconcile pass on the caller thread, per configured tenant. Also
   evaluates health, once, over every tenant's keys and remote state, and
   sends each organization its synthetic heartbeat (isaac-an14)."
  ([] (tick! {}))
  ([{:keys [now root config] :as opts}]
   ;; :door-up? is read from opts explicitly — a destructured local of the
   ;; same name would shadow the door-up? fn, and the scheduler calls (tick! {}).
   (let [up?     (if (contains? opts :door-up?) (:door-up? opts) (door-up?))
         root    (or root (feature-root))
         now     (or now (memory/now) (Instant/now))
         _       (ensure-contributions!)
         cfg     (or config (load-cfg))
         entries (all)
         ids     (tenants/ids cfg)
         surveys (mapv #(survey-tenant now cfg entries %) ids)
         remotes (apply merge {} (map :remote surveys))
         h-state (health/load-state root)
         health-tenants (health-tenants surveys h-state (= 1 (count ids)))
         conditions (health/evaluate {:now      now
                                      :config   cfg
                                      :tenants  health-tenants
                                      :state    h-state
                                      :remote   remotes
                                      :door-up? up?})]
     (health/log-conditions! conditions h-state)
     (doseq [survey surveys]
       (execute-tenant! root survey))
     (let [applied (health/apply! {:now        now
                                   :config     cfg
                                   :root       root
                                   :state      h-state
                                   :conditions conditions})]
       (heartbeat/send-all! {:root root :config cfg :now now :tenants ids})
       applied))))
