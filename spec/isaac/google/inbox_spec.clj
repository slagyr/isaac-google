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

  (it "reports :unknown for a message-id it has never seen"
    (should= :unknown (sut/status "/test/isaac" "never-seen")))

  (it "reports :pending for an accepted, undrained record"
    (sut/accept! "/test/isaac" {:message-id "m-6" :type "t" :data {}})
    (should= :pending (sut/status "/test/isaac" "m-6")))

  (it "reports :done once the worker marks it done"
    (sut/accept! "/test/isaac" {:message-id "m-7" :type "t" :data {}})
    (sut/mark-done! "/test/isaac" "m-7")
    (should= :done (sut/status "/test/isaac" "m-7")))

  (it "reports :failed once the worker marks it failed"
    (sut/accept! "/test/isaac" {:message-id "m-8" :type "t" :data {}})
    (sut/mark-failed! "/test/isaac" "m-8")
    (should= :failed (sut/status "/test/isaac" "m-8")))

  (it "moves an unhandled record out of pending/ into unhandled/"
    (sut/accept! "/test/isaac" {:message-id "m-9" :type "unknown/type" :data {}})
    (sut/mark-unhandled! "/test/isaac" "m-9")
    (should-not (fs/exists? (fs/instance) "/test/isaac/google/inbox/pending/m-9.edn"))
    (should (fs/exists? (fs/instance) "/test/isaac/google/inbox/unhandled/m-9.edn"))
    (should= [] (sut/pending "/test/isaac"))
    (should= 1 (count (sut/unhandled "/test/isaac"))))

  (it "reports :unhandled once a record is parked"
    (sut/accept! "/test/isaac" {:message-id "m-10" :type "unknown/type" :data {}})
    (sut/mark-unhandled! "/test/isaac" "m-10")
    (should= :unhandled (sut/status "/test/isaac" "m-10")))

  (it "reports no unhandled records when none have been parked"
    (should= [] (sut/unhandled "/test/isaac")))
  )
