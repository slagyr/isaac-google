(ns isaac.google.health
  "Health evaluation, attention throttle, status table, and health.edn state.

   Health is a heartbeat, not a chatter monitor. Isaac does not publish that
   heartbeat — a Cloud Scheduler job does, on the organization's own topic,
   as a Google APIs service account inside GCP — so the watch is arrival
   recency against the interval the operator says to expect one on, never a
   send paired with an arrival (isaac-clly). Silence is judged once per
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
    [clojure.string :as str]
    [isaac.comm.delivery.queue :as queue]
    [isaac.fs :as fs]
    [isaac.google.tenants :as tenants]
    [isaac.logger :as log]
    [isaac.nexus :as nexus])
  (:import (java.time Duration Instant)))

(def DEFAULT-SILENT-HOURS 6)

(def DEFAULT-HEARTBEAT-GRACE-MS
  "Slack past the moment a heartbeat was due before it counts as missed:
   scheduler jitter plus Pub/Sub delivery. One minute, the deadline a
   self-published heartbeat used to get to reach the door."
  60000)

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

(defn heartbeat-interval-ms
  "How often this organization's heartbeat is published — by Cloud Scheduler,
   not by Isaac. nil when nobody named an interval, which is also how an
   organization says it watches for no heartbeat at all.

   There is deliberately no default. A heartbeat Isaac publishes can be timed
   from the publish; one published outside can only be timed against what the
   operator says to expect, so a guess here would be a watchdog barking at a
   schedule nobody runs — or, worse, not barking (isaac-clly)."
  [config id]
  (let [ms (as-long (get-in (tenant-health config id) [:heartbeat :expected-interval-ms]) nil)]
    (when (and ms (pos? ms)) ms)))

(defn heartbeat-grace-ms
  "Slack past the expected arrival before this organization calls it missed."
  [config id]
  (as-long (get-in (tenant-health config id) [:heartbeat :grace-ms])
           DEFAULT-HEARTBEAT-GRACE-MS))

(defn heartbeat-watched?
  "Does this organization watch for a heartbeat? Naming the interval is what
   turns the watch on — there is no separate switch, because a switch that
   could be on over an interval nobody set is exactly the inert watchdog this
   whole arrangement exists to make impossible (isaac-clly)."
  [config id]
  (some? (heartbeat-interval-ms config id)))

(defn heartbeat-budget-ms
  "How long this organization may go with no heartbeat arriving: the interval
   it expects one on, plus grace.

   An organization that named no interval is a config error the loader
   refuses (`check-heartbeat`). Should one reach here anyway the budget is
   the grace alone, so the watch fires early rather than falling silent —
   between the two mistakes, only one of them is quiet."
  [config id]
  (+ (or (heartbeat-interval-ms config id) 0)
     (heartbeat-grace-ms config id)))

;; region ----- the config check -----

(def INTERVAL-KEY-LEAF ["health" "heartbeat" "expected-interval-ms"])

(defn heartbeat-interval-key
  "The config key an operator sets to say how often `id`'s heartbeat is
   published."
  [config id]
  (str/join "." (concat (map name (tenants/config-path config (or id :<organization>)))
                        INTERVAL-KEY-LEAF)))

(def ENABLED-KEY-LEAF ["health" "heartbeat" "enabled"])

(defn heartbeat-enabled-key
  "The retired on/off key, named where an operator will recognise it."
  [config id]
  (str/join "." (concat (map name (tenants/config-path config (or id :<organization>)))
                        ENABLED-KEY-LEAF)))

(def RETIRED-ENABLED-REASON
  "is retired and ignored: naming health.heartbeat.expected-interval-ms is what turns the watch on now, because only the publisher's schedule can say what late means. No heartbeat is being watched for this organization. Unset it (isaac-clly)")

(def MISSING-INTERVAL-REASON
  "names no interval: the heartbeat is published from outside Isaac (a Cloud Scheduler job on the topic), so only the schedule it runs on can say what late means — set it, or configure no heartbeat at all (isaac-clly)")

(def BAD-INTERVAL-REASON
  "must be a positive number of milliseconds — the interval the external publisher's schedule runs on")

(defn check-heartbeat
  "An organization that configured a heartbeat must say how often to expect
   one. Half-configured, the watch would read as set up and never fire: with
   no interval there is nothing to be late against, and an arrival watch with
   no deadline is an inert watchdog wearing a healthy face.

   This is the same refusal isaac-286x made of a missing service-account key,
   pointed at what the heartbeat actually needs now. The key is gone — Cloud
   Scheduler publishes as a Google APIs service account inside GCP, so there
   is no credential to name and nothing for
   `constraints/iam.disableServiceAccountKeyCreation` to refuse. What is left
   to get wrong is the interval, so that is what the loader checks."
  [{:keys [config]}]
  (let [entries (sort-by key (tenants/tenants config))
        for-each (fn [f] (vec (keep f entries)))]
    {:errors
     ;; The only hard refusal: an interval was named and cannot work. The
     ;; operator asked for a watch and would not get one.
     (for-each (fn [[id tenant]]
                 (let [heartbeat (get-in tenant [:health :heartbeat])]
                   (when (and (not (heartbeat-watched? config id))
                              (some? (:expected-interval-ms heartbeat)))
                     {:key (heartbeat-interval-key config id) :value BAD-INTERVAL-REASON}))))

     ;; Everything else is a warning. Leftover config is not a reason to
     ;; refuse to start: a host that merely receives is working, and the
     ;; retired key changes nothing now that the interval is the switch.
     :warnings
     (for-each (fn [[id tenant]]
                 (let [heartbeat (get-in tenant [:health :heartbeat])]
                   (cond
                     (heartbeat-watched? config id) nil

                     (some? (:expected-interval-ms heartbeat)) nil ; already an error

                     (contains? heartbeat :enabled)
                     {:key (heartbeat-enabled-key config id) :value RETIRED-ENABLED-REASON}

                     (and (map? heartbeat) (seq heartbeat))
                     {:key (heartbeat-interval-key config id) :value MISSING-INTERVAL-REASON}))))}))

;; endregion

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
  "Nothing has arrived from the organization's heartbeat for longer than it
   expects between them. That is the pipeline failing where no chat traffic
   would show it.

   The publisher is outside Isaac — a Cloud Scheduler job on the topic — so
   the only evidence is arrival recency. This used to pair a send with an
   arrival, and the moment Isaac stopped publishing there was no send: no
   `:heartbeat-sent-at`, no elapsed, and a condition that could not fire
   while the config still read as enabled. A health check that cannot fail
   is not a health check (isaac-clly).

   An organization that has never seen one is missed, not unknown: silence
   from the start is the same pipeline failure as silence after a year, and
   a topic that was never wired up is precisely the case a first-arrival
   grace period would hide forever. A host that has only just booted is
   covered by `:door-unreached`, which suppresses this."
  [now config state tenant]
  (when (heartbeat-watched? config tenant)
    (let [budget  (heartbeat-budget-ms config tenant)
          arrived (->instant (get-in state [:last-heartbeat-at tenant]))
          elapsed (millis-between arrived (->instant now))]
      (when (or (nil? arrived) (and elapsed (>= elapsed budget)))
        [{:kind        :heartbeat-missed
          :tenant      tenant
          :deadline-ms budget}]))))

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
  "A heartbeat arrived at the door. It is deliberately not
   a `last-event`: nobody spoke, so it never appears in the status table's
   last-event column and never starts anything. It does count as having
   heard from the organization — see `last-seen-for` (isaac-an14)."
  [root tenant at]
  (let [state (load-state root)]
    (save-state! root (assoc-in state [:last-heartbeat-at tenant] (iso at)))))

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
