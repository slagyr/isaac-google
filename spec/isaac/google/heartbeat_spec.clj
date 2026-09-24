(ns isaac.google.heartbeat-spec
  (:require
    [isaac.google.heartbeat :as sut]
    [speclj.core :refer :all]))

(describe "the heartbeat's marker at the door"

  ;; Isaac no longer publishes a heartbeat — Cloud Scheduler does, and this
  ;; module's whole half is recognising one when it arrives (isaac-clly).

  (it "knows its own marker by event type"
    (should (sut/heartbeat? {:type sut/CE-TYPE :message-id "hb-1"})))

  (it "knows its own marker in the payload"
    (should (sut/heartbeat? {:type nil :data {:isaac-heartbeat true}}))
    (should (sut/heartbeat? {:type nil :data {"isaac-heartbeat" true}})))

  (it "is not fooled by an ordinary Chat push"
    (should-not (sut/heartbeat? {:type "google.workspace.chat.message.v1.created"
                                 :data {:message {:name "spaces/ENG/messages/1"}}})))

  ;; Nothing in this module publishes any more: no send!, no send-all!, and
  ;; no Pub/Sub client to carry them (isaac-clly).
  (it "offers nothing that publishes"
    (should= #{'CE-TYPE 'heartbeat?}
             (set (keys (ns-publics 'isaac.google.heartbeat)))))
  )
