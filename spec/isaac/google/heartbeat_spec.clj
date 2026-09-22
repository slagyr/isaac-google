(ns isaac.google.heartbeat-spec
  (:require
    [isaac.fs :as fs]
    [isaac.google.health :as health]
    [isaac.google.heartbeat :as sut]
    [isaac.google.pubsub :as pubsub]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all])
  (:import (java.time Instant)))

(def now (Instant/parse "2026-09-18T12:00:00Z"))

(def config
  {:google {:tonotop {:topic "projects/marigold/topics/isaac"}
            :acme    {:topic "projects/acme/topics/isaac" :health {:heartbeat {:enabled false}}}}})

(describe "the synthetic heartbeat"

  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (log/capture-logs (example))))

  (context "recognising one at the door"

    (it "knows its own marker by event type"
      (should (sut/heartbeat? {:type sut/CE-TYPE :message-id "hb-1"})))

    (it "knows its own marker in the payload"
      (should (sut/heartbeat? {:type nil :data {:isaac-heartbeat true}}))
      (should (sut/heartbeat? {:type nil :data {"isaac-heartbeat" true}})))

    (it "is not fooled by an ordinary Chat push"
      (should-not (sut/heartbeat? {:type "google.workspace.chat.message.v1.created"
                                   :data {:message {:name "spaces/ENG/messages/1"}}}))))

  (context "publishing one"

    (it "publishes a marked message to the organization's topic and remembers when"
      (let [published (atom [])]
        (with-redefs [pubsub/publish! (fn [_cfg id message]
                                        (swap! published conj [id message])
                                        {:message-id "m-1"})]
          (sut/send-all! {:root "/test/isaac" :config config :now now :tenants [:tonotop]}))
        (let [[id message] (first @published)]
          (should= :tonotop id)
          (should= sut/CE-TYPE (get-in message [:attributes "ce-type"]))
          (should= true (get-in message [:data :isaac-heartbeat])))
        (should= {:tonotop "2026-09-18T12:00:00Z"}
                 (:heartbeat-sent-at (health/load-state "/test/isaac")))))

    (it "skips an organization that turned the heartbeat off"
      (let [published (atom [])]
        (with-redefs [pubsub/publish! (fn [_cfg id _message] (swap! published conj id) {:message-id "m"})]
          (sut/send-all! {:root "/test/isaac" :config config :now now :tenants [:tonotop :acme]}))
        (should= [:tonotop] @published)))

    ;; Nothing was published, so nothing is in flight to miss: the failure is
    ;; the news, not a heartbeat that never left.
    (it "remembers nothing and warns when the publish fails"
      (with-redefs [pubsub/publish! (fn [_cfg _id _message] {:error "no publish permission"})]
        (sut/send-all! {:root "/test/isaac" :config config :now now :tenants [:tonotop]}))
      (should= nil (:heartbeat-sent-at (health/load-state "/test/isaac")))
      (should-contain :google/heartbeat-failed (map :event @log/captured-logs)))

    (it "keeps one organization's failure from stopping the next"
      (let [published (atom [])]
        (with-redefs [pubsub/publish! (fn [_cfg id _message]
                                        (if (= :tonotop id)
                                          (throw (ex-info "boom" {}))
                                          (do (swap! published conj id) {:message-id "m"})))]
          (sut/send-all! {:root    "/test/isaac"
                          :config  {:google {:tonotop {:topic "t"} :acme {:topic "a"}}}
                          :now     now
                          :tenants [:tonotop :acme]}))
        (should= [:acme] @published))))

  )
