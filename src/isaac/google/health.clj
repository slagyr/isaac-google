(ns isaac.google.health
  "Health evaluation, attention throttle, status table, and health.edn state."
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [isaac.comm.delivery.queue :as queue]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus])
  (:import (java.time Duration Instant)))

(def DEFAULT-SILENT-HOURS 6)

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

(defn- silent-hours-cfg [config]
  (let [v (or (get-in config [:google :health :silent-after-hours])
              (get-in config [:google/health :silent-after-hours]))]
    (cond
      (int? v) v
      (number? v) (long v)
      (string? v) (or (parse-long v) DEFAULT-SILENT-HOURS)
      :else DEFAULT-SILENT-HOURS)))

(defn- expired? [now expires-at]
  (let [now (->instant now)
        exp (->instant expires-at)]
    (boolean (and now exp (not (.isAfter exp now))))))

(defn- silent-hours [now last-event-at]
  (let [now (->instant now)
        at  (->instant last-event-at)]
    (when (and now at)
      (hours-between at now))))

(defn- condition-id [condition]
  (if (= :door-unreached (:kind condition))
    [:door-unreached]
    [(:kind condition) (:key condition)]))

(defn evaluate
  "Pure: state + now + config → vector of failing conditions.
   An unreached door (server up, no hit since boot) is exposure, not Google —
   it suppresses per-key silence/expiry so the report is the funnel, not Chat."
  [{:keys [now config keys state remote door-up?]}]
  (if (and door-up? (not (:door-last-hit state)))
    [{:kind :door-unreached}]
    (let [threshold (silent-hours-cfg config)]
      (vec
        (mapcat
          (fn [k]
            (let [exp   (get-in remote [k :expires-at])
                  last  (get-in state [:last-event-at k])
                  hours (silent-hours now last)]
              (cond-> []
                (and exp (expired? now exp))
                (conj {:kind :expired :key k})

                (and hours (>= hours threshold))
                (conj {:kind :silent :key k :silent-hours hours}))))
          (or keys []))))))

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

(defn- condition-content [condition]
  (case (:kind condition)
    :silent
    (str (:key condition) " no event for " (:silent-hours condition) "h")

    :expired
    (str (:key condition) " expired")

    :door-unreached
    "Funnel door has answered nothing since boot"

    (str (name (:kind condition)) " " (:key condition))))

(defn- log-condition! [condition]
  (case (:kind condition)
    :silent
    (log/warn :google/silent :key (:key condition) :silent-hours (:silent-hours condition))

    :expired
    (log/warn :google/expired :key (:key condition))

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

(defn- present-ids [conditions]
  (set (map condition-id conditions)))

(defn- prune-notified [notified present]
  (into {} (filter (fn [[id _]] (contains? present id)) notified)))

(defn log-conditions! [conditions]
  (if (empty? conditions)
    (log/debug :google/health-ok)
    (doseq [c conditions]
      (log-condition! c))))

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
