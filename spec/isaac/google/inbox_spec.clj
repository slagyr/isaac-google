(ns isaac.google.inbox-spec
  (:require
    [isaac.fs :as fs]
    [isaac.google.inbox :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "google inbox"

  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "persists a push as a pending record and acknowledges it as new"
    (let [event {:message-id "m-1"
                 :type       "google.workspace.chat.message.v1.created"
                 :data       {:message {:name "spaces/AAA/messages/1"}}}]
      (should= :new (sut/accept! "/test/isaac" event))
      (should (fs/exists? (fs/instance) "/test/isaac/google/inbox/pending/m-1.edn"))))

  (it "dedupes a repeated messageId as already-seen"
    (let [event {:message-id "m-5"
                 :type       "google.workspace.chat.message.v1.created"
                 :data       {}}]
      (should= :new (sut/accept! "/test/isaac" event))
      (should= :duplicate (sut/accept! "/test/isaac" event))
      (should= 1 (count (sut/pending "/test/isaac")))))
  )
