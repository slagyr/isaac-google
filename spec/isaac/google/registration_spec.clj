(ns isaac.google.registration-spec
  (:require
    [isaac.fs :as fs]
    [isaac.google.registration :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all])
  (:import (java.time Instant)))

(def now (Instant/parse "2026-09-18T12:00:00Z"))
(def root "/test/google-reg")

(describe "google registration reconcile"

  (it "treats 18 hours until expiry as inside a 24 hour window"
    (should (sut/within-window? now "2026-09-19T06:00:00Z" 24)))

  (it "treats 6 days until expiry as outside a 24 hour window"
    (should-not (sut/within-window? now "2026-09-24T12:00:00Z" 24)))

  (it "treats an expiry already in the past as inside the window"
    (should (sut/within-window? now "2026-09-18T09:00:00Z" 24)))

  (it "creates when a configured key has no Google subscription"
    (should= [{:op :create :key "spaces/ENG"}]
             (sut/plan {:configured #{"spaces/ENG"}
                        :remote     {}
                        :now        now
                        :renew-within-hours 24})))

  (it "renews when expiry is within the window"
    (should= [{:op :renew :key "spaces/ENG" :name "subscriptions/s-eng"}]
             (sut/plan {:configured #{"spaces/ENG"}
                        :remote     {"spaces/ENG" {:name "subscriptions/s-eng"
                                                   :expires-at "2026-09-19T06:00:00Z"}}
                        :now        now
                        :renew-within-hours 24})))

  (it "noops when expiry is outside the window"
    (should= [{:op :noop :key "spaces/PROD" :name "subscriptions/s-prod"}]
             (sut/plan {:configured #{"spaces/PROD"}
                        :remote     {"spaces/PROD" {:name "subscriptions/s-prod"
                                                    :expires-at "2026-09-24T12:00:00Z"}}
                        :now        now
                        :renew-within-hours 24})))

  (it "deletes when a remote subscription's key left config"
    (should= [{:op :noop :key "spaces/ENG" :name "subscriptions/s-eng"}
              {:op :delete :key "spaces/PROD" :name "subscriptions/s-prod"}]
             (sut/plan {:configured #{"spaces/ENG"}
                        :remote     {"spaces/ENG"  {:name "subscriptions/s-eng"
                                                    :expires-at "2026-09-24T12:00:00Z"}
                                     "spaces/PROD" {:name "subscriptions/s-prod"
                                                    :expires-at "2026-09-24T12:00:00Z"}}
                        :now        now
                        :renew-within-hours 24})))

  (context "state persistence"

    (around [example]
      (nexus/-with-nexus {:root root :fs (fs/mem-fs)}
        (example)))

    (it "writes subscription name and expiry under google/registrations.edn"
      (let [state {"spaces/ENG" {:name "subscriptions/s-eng" :expires-at "2026-09-25T12:00:00Z"}}]
        (sut/save-state! root state)
        (should (fs/exists? (fs/instance) (str root "/google/registrations.edn")))
        (should= state (sut/load-state root))))
    )
  )
