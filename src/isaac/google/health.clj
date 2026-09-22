(ns isaac.google.health
  "Health evaluation, attention throttle, status table, and health.edn state.

   Health is a heartbeat, not a chatter monitor. Silence is judged once per
   Google organization — \"nothing at all from this tenant in
   `silent-after-hours`\" — because a quiet space is normal and a quiet
   *organization* is not. It is judged over every key the health state has
   heard from rather than the keys that happen to be registered, since one
   registration can cover a whole workspace. Expiry stays per registration
   key, since a key is what Google expires.

   Every condition is news only when it appears or disappears: the tick logs
   and posts on the transition, never once a tick (isaac-an14)."
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [isaac.comm.delivery.queue :as queue]
    [isaac.fs :as fs]
    [isaac.google.tenants :as tenants]
    [isaac.logger :as log]
    [isaac.nexus :as nexus])
  (:import (java.time Duration Instant)))

(def DEFAULT-SILENT-HOURS 6)
(def DEFAULT-HEARTBEAT-DEADLINE-MS 60000)

(defn- runtime-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn state-path [root]
  (str root "/google/health.edn"))

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

(defn- ->instant [v]
  (cond
    (instance? Instant v) v
    (string? v) (Instant/parse v)
    :else v))

(defn- hours-between [earlier later]
  (when (and earlier later)
    (.toHours (Duration/between earlier later))))

(defn- millis-between [earlier later]
  (when (and earlier later)
    (.toMillis (Duration/between earlier later))))

(defn- as-long [v fallback]
  (cond
    (int? v) v
    (number? v) (long v)
    (string? v) (or (parse-long v) fallback)
    :else fallback))

(defn- tenant-health
  "One organization's :health settings. Each organization sets its own."
  [config id]
  (get-in (tenants/tenants config) [id :health]))

(defn silent-hours-cfg
  "Hours without any event from this organization before it counts as silent."
  [config id]
  (as-long (:silent-after-hours (tenant-health config id)) DEFAULT-SILENT-HOURS))

(defn heartbeat-enabled?
  "Does this organization get a synthetic heartbeat? On unless it says no —
   the heartbeat is what proves the push pipeline when nobody is talking."
  [config id]
  (not (false? (get-in (tenant-health config id) [:heartbeat :enabled]))))

(defn heartbeat-deadline-ms
  "How long a published heartbeat has to reach the door."
  [config id]
  (as-long (get-in (tenant-health config id) [:heartbeat :deadline-ms])
           DEFAULT-HEARTBEAT-DEADLINE-MS))

(defn- expired? [now expires-at]
  (let [now (->instant now)
        exp (->instant expires-at)]
    (boolean (and now exp (not (.isAfter exp now))))))

(defn- condition-id [condition]
  (if (= :door-unreached (:kind condition))
    [:door-unreached]
    [(:kind condition) (or (:key condition) (:tenant condition))]))

(defn- last-seen-for
  "The last time anything at all arrived from this organization: an event
   under any key the health state holds for it, or its own heartbeat coming
   back through the door.

   The keys are whatever has spoken, not whatever is registered — one
   registration can cover a whole workspace (`spaces/-`), and then no event
   is ever recorded under a registration key. And a heartbeat, though it is
   not an event (nobody spoke), is proof the organization is reachable,
   which is exactly what silence is asking about (isaac-an14)."
  [state tenant keys]
  (->> (conj (mapv #(get-in state [:last-event-at %]) keys)
             (get-in state [:last-heartbeat-at tenant]))
       (keep identity)
       (map ->instant)
       (sort)
       (last)))

(defn- expired-conditions [now remote keys]
  (for [k    keys
        :let [exp (get-in remote [k :expires-at])]
        :when (and exp (expired? now exp))]
    {:kind :expired :key k}))

(defn- silent-conditions [now config state tenant keys]
  (let [threshold (silent-hours-cfg config tenant)
        hours     (hours-between (last-seen-for state tenant keys) (->instant now))]
    (when (and hours (>= hours threshold))
      [{:kind :silent :tenant tenant :silent-hours hours}])))

(defn- heartbeat-conditions
  "A heartbeat published `deadline-ms` ago that never came back through the
   door is the pipeline failing where no chat traffic would show it."
  [now config state tenant]
  (when (heartbeat-enabled? config tenant)
    (let [sent    (->instant (get-in state [:heartbeat-sent-at tenant]))
          arrived (->instant (get-in state [:last-heartbeat-at tenant]))
          elapsed (millis-between sent (->instant now))]
      (when (and elapsed
                 (>= elapsed (heartbeat-deadline-ms config tenant))
                 (or (nil? arrived) (.isBefore arrived sent)))
        [{:kind        :heartbeat-missed
          :tenant      tenant
          :deadline-ms (heartbeat-deadline-ms config tenant)}]))))

(defn evaluate
  "Pure: state + now + config → vector of failing conditions.

   `tenants` is `[{:tenant id :keys [key …]} …]` — one entry per configured
   Google organization with the registration keys it owns. Silence and the
   heartbeat are judged per organization; expiry per key.

   An unreached door (server up, no hit since boot) is exposure, not Google —
   it suppresses the rest so the report is the funnel, not Chat."
  [{:keys [now config tenants state remote door-up?]}]
  (if (and door-up? (not (:door-last-hit state)))
    [{:kind :door-unreached}]
    (vec
      (mapcat
        (fn [{:keys [tenant keys]}]
          (concat (expired-conditions now remote keys)
                  (silent-conditions now config state tenant keys)
                  (heartbeat-conditions now config state tenant)))
        (or tenants [])))))

(defn should-notify?
  "True when this condition has not yet been posted (or was cleared)."
  [condition state]
  (nil? (get-in state [:notified-at (condition-id condition)])))

(defn- door-cell [state]
  (if-let [hit (:door-last-hit state)]
    (str "door: " hit)
    "door: never"))

(defn status-lines
  "Render one status line per key: key  expires  last-event  door."
  [{:keys [keys remote state]}]
  (let [door (door-cell state)]
    (mapv (fn [k]
            (let [expires (or (get-in remote [k :expires-at]) "unknown")
                  last    (or (get-in state [:last-event-at k]) "never")]
              (str k "  " expires "  " last "  " door)))
          (or keys []))))

(defn- notify-coords [cfg]
  (get-in cfg [:attention :notify]))

(defn- subject [condition]
  (let [s (or (:key condition) (:tenant condition))]
    (if (keyword? s) (name s) (str s))))

(defn- condition-content [condition]
  (case (:kind condition)
    :silent
    (str (subject condition) " no event for " (:silent-hours condition) "h")

    :expired
    (str (subject condition) " expired")

    :heartbeat-missed
    (str (subject condition) " heartbeat missed — no push within "
         (:deadline-ms condition) "ms")

    :door-unreached
    "Funnel door has answered nothing since boot"

    (str (name (:kind condition)) " " (subject condition))))

(defn- log-condition! [condition]
  (case (:kind condition)
    :silent
    (log/warn :google/silent :tenant (:tenant condition) :silent-hours (:silent-hours condition))

    :expired
    (log/warn :google/expired :key (:key condition))

    :heartbeat-missed
    (log/warn :google/heartbeat-missed :tenant (:tenant condition) :deadline-ms (:deadline-ms condition))

    :door-unreached
    (log/warn :google/door-unreached)

    nil))

(defn- enqueue-attention! [cfg content]
  (when-let [{:keys [comm target]} (notify-coords cfg)]
    (queue/enqueue! {:comm    (if (keyword? comm) (name comm) (str comm))
                     :target  target
                     :content content})))

(defn- iso [instant]
  (when instant
    (str (->instant instant))))

(defn record-last-event! [root key at]
  (let [state (load-state root)
        next  (assoc-in state [:last-event-at key] (iso at))]
    (save-state! root next)))

(defn record-door-hit! [root at]
  (let [state (load-state root)
        next  (assoc state :door-last-hit (iso at))]
    (save-state! root next)))

(defn record-heartbeat!
  "A synthetic heartbeat came back through the door. It is deliberately not
   a `last-event`: nobody spoke, so it never appears in the status table's
   last-event column and never starts anything. It does count as having
   heard from the organization — see `last-seen-for` (isaac-an14)."
  [root tenant at]
  (let [state (load-state root)]
    (save-state! root (assoc-in state [:last-heartbeat-at tenant] (iso at)))))

(defn record-heartbeat-sent! [root tenant at]
  (let [state (load-state root)]
    (save-state! root (assoc-in state [:heartbeat-sent-at tenant] (iso at)))))

(defn- present-ids [conditions]
  (set (map condition-id conditions)))

(defn- prune-notified [notified present]
  (into {} (filter (fn [[id _]] (contains? present id)) notified)))

(defn log-conditions!
  "Log the transitions only: each condition new since `state` was written,
   then one clear per condition that was notified and has now gone. A
   condition that is merely still present says nothing — at an hourly tick
   the news is the change, not the tick (isaac-an14)."
  [conditions state]
  (let [present (present-ids conditions)]
    (doseq [condition conditions
            :when     (should-notify? condition state)]
      (log-condition! condition))
    (doseq [[id at] (or (:notified-at state) {})
            :when   (not (contains? present id))]
      (log/info :google/health-cleared :kind (first id) :subject (second id) :since at))
    (when (empty? conditions)
      (log/debug :google/health-ok))))

(defn apply!
  "Enqueue attention (once per condition) and persist notified-at. Does not log —
   call log-conditions! first so :google/expired precedes :google/renewed."
  [{:keys [now config root state conditions]}]
  (let [now-iso  (iso now)
        notified (or (:notified-at state) {})
        present  (present-ids conditions)
        kept     (prune-notified notified present)]
    (if (empty? conditions)
      (do
        (save-state! root (assoc state :notified-at {}))
        [])
      (let [next-notified
            (reduce
              (fn [acc condition]
                (if (should-notify? condition {:notified-at acc})
                  (do
                    (enqueue-attention! config (condition-content condition))
                    (assoc acc (condition-id condition) now-iso))
                  acc))
              kept
              conditions)]
        (save-state! root (assoc state :notified-at next-notified))
        conditions))))
