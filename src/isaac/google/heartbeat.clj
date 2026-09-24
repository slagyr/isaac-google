(ns isaac.google.heartbeat
  "The heartbeat's marker, and nothing else.

   The failure mode of this pipeline is silence: an expired subscription, a
   broken Pub/Sub binding and a dead Funnel all look exactly like nobody
   talking. So a real message is published to the organization's own topic on
   a schedule, delivered by Google to the real door, verified like any other
   push. What arrives proves topic, subscription, Funnel, OIDC verification
   and the door, with no chat traffic involved (isaac-an14).

   Isaac does not publish it. A Cloud Scheduler job does, as a Google APIs
   service account *inside* GCP: the identity is never exported, so there is
   no key to steal and none for
   `constraints/iam.disableServiceAccountKeyCreation` to refuse — the org
   policy that left yopp running with no silence detector at all. It is also
   a better probe than Isaac publishing to itself, because the message
   originates outside the process being tested rather than in a loop Isaac
   controls at both ends (isaac-clly).

   So this namespace is one half of a handshake it no longer starts: the
   marker the door matches on, and the watch is `isaac.google.health`.

   A heartbeat is not an event: the door records it as a heartbeat only, so
   it can never quiet the silence watch or start a turn.")

(def CE-TYPE "isaac.google/heartbeat")

(defn heartbeat?
  "Is this push a heartbeat? Named twice — by ce-type and in the payload — so
   a subscription that drops attributes still tells the door what it is
   holding, and so the Cloud Scheduler job can set whichever of the two its
   operator finds easier to get right."
  [event]
  (let [data (:data event)]
    (boolean (or (= CE-TYPE (:type event))
                 (and (map? data)
                      (or (true? (:isaac-heartbeat data))
                          (true? (get data "isaac-heartbeat"))))))))
