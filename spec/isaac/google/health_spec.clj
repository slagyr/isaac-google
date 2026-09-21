(ns isaac.google.health-spec
  (:require
    [isaac.google.health :as sut]
    [speclj.core :refer :all])
  (:import (java.time Instant)))

(def now (Instant/parse "2026-09-18T12:00:00Z"))

(describe "google health evaluation"

  (it "flags silence when last event is older than the threshold"
    (let [conditions (sut/evaluate
                       {:now    now
                        :config {:google {:tonotop {:health {:silent-after-hours 6}}}}
                        :keys   ["spaces/ENG"]
                        :state  {:last-event-at {"spaces/ENG" "2026-09-18T04:00:00Z"}
                                 :door-last-hit "2026-09-18T11:00:00Z"}
                        :remote {"spaces/ENG" {:expires-at "2026-09-25T12:00:00Z"}}})]
      (should= [{:kind :silent :key "spaces/ENG" :silent-hours 8}]
               conditions)))

  (it "flags expiry when Google expiry is in the past"
    (let [conditions (sut/evaluate
                       {:now    now
                        :config {:google {:tonotop {:health {:silent-after-hours 6}}}}
                        :keys   ["spaces/ENG"]
                        :state  {:last-event-at {"spaces/ENG" "2026-09-18T11:30:00Z"}
                                 :door-last-hit "2026-09-18T11:00:00Z"}
                        :remote {"spaces/ENG" {:expires-at "2026-09-18T09:00:00Z"}}})]
      (should= [{:kind :expired :key "spaces/ENG"}]
               conditions)))

  (it "flags an unreached door when the door is up and door-last-hit is missing"
    (let [conditions (sut/evaluate
                       {:now     now
                        :config  {:google {:tonotop {:health {:silent-after-hours 6}}}}
                        :keys    ["spaces/ENG"]
                        :door-up? true
                        :state   {:last-event-at {"spaces/ENG" "2026-09-18T04:00:00Z"}}
                        :remote  {"spaces/ENG" {:expires-at "2026-09-25T12:00:00Z"}}})]
      (should= [{:kind :door-unreached}] conditions)))

  (it "does not flag the door when the server is not up"
    (let [conditions (sut/evaluate
                       {:now    now
                        :config {:google {:tonotop {:health {:silent-after-hours 6}}}}
                        :keys   ["spaces/ENG"]
                        :state  {:last-event-at {"spaces/ENG" "2026-09-18T11:30:00Z"}}
                        :remote {"spaces/ENG" {:expires-at "2026-09-25T12:00:00Z"}}})]
      (should= [] conditions)))

  (it "does not re-notify a condition already notified and still present"
    (should-not (sut/should-notify?
                  {:kind :silent :key "spaces/ENG"}
                  {:notified-at {[:silent "spaces/ENG"] "2026-09-18T11:00:00Z"}})))

  (it "notifies a condition that has not been posted yet"
    (should (sut/should-notify?
              {:kind :silent :key "spaces/ENG"}
              {:notified-at {}})))

  (it "renders a status table with key expiry last-event and door"
    (should= ["spaces/ENG  2026-09-25T12:00:00Z  2026-09-18T11:30:00Z  door: never"]
             (sut/status-lines
               {:keys   ["spaces/ENG"]
                :remote {"spaces/ENG" {:expires-at "2026-09-25T12:00:00Z"}}
                :state  {:last-event-at {"spaces/ENG" "2026-09-18T11:30:00Z"}}})))

  )
