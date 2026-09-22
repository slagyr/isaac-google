(ns isaac.google.heartbeat
  "A synthetic push, one per organization per registration tick.

   The failure mode of this pipeline is silence: an expired subscription, a
   broken Pub/Sub binding and a dead Funnel all look exactly like nobody
   talking. So the tick sends itself a real message — published to the
   organization's own topic, delivered by Google to the real door, verified
   like any other push — and expects it back within a deadline. What comes
   back proves topic, subscription, Funnel, OIDC verification and the door,
   with no chat traffic involved (isaac-an14).

   A heartbeat is not an event: the door records it as a heartbeat only, so
   it can never quiet the silence watch or start a turn."
  (:require
    [isaac.google.health :as health]
    [isaac.google.pubsub :as pubsub]
    [isaac.logger :as log]))

(def CE-TYPE "isaac.google/heartbeat")

(defn heartbeat?
  "Is this push the tick's own synthetic message? Named twice — by ce-type
   and in the payload — so a subscription that drops attributes still tells
   the door what it is holding."
  [event]
  (let [data (:data event)]
    (boolean (or (= CE-TYPE (:type event))
                 (and (map? data)
                      (or (true? (:isaac-heartbeat data))
                          (true? (get data "isaac-heartbeat"))))))))

(defn- message [tenant now]
  {:data       {:isaac-heartbeat true
                :tenant          (name tenant)
                :at              (str now)}
   :attributes {"ce-type" CE-TYPE}})

(defn send!
  "Publish one heartbeat for `tenant` and remember when it left. A publish
   that fails records nothing — there is no heartbeat in flight to miss."
  [{:keys [root config tenant now]}]
  (let [{:keys [error message-id]} (pubsub/publish! config tenant (message tenant now))]
    (if error
      (log/warn :google/heartbeat-failed :tenant tenant :reason error)
      (do
        (health/record-heartbeat-sent! root tenant now)
        (log/debug :google/heartbeat-sent :tenant tenant :message-id message-id)))))

(defn send-all!
  "One heartbeat per organization that wants one. One organization's failure
   never costs the next its heartbeat."
  [{:keys [root config now tenants]}]
  (doseq [tenant tenants
          :when  (health/heartbeat-enabled? config tenant)]
    (try
      (send! {:root root :config config :tenant tenant :now now})
      (catch Exception e
        (log/warn :google/heartbeat-failed :tenant tenant :reason (.getMessage e))))))
