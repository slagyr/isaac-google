(ns isaac.google.health-spec
  (:require
    [isaac.google.health :as sut]
    [isaac.logger :as log]
    [speclj.core :refer :all])
  (:import (java.time Instant)))

(def now (Instant/parse "2026-09-18T12:00:00Z"))

(def config
  {:google {:tonotop {:health {:silent-after-hours 6}}}})

(defn- evaluate [opts]
  (sut/evaluate (merge {:now now :config config} opts)))

(describe "google health evaluation"

  (context "silence is one condition per organization (isaac-an14)"

    ;; A quiet space is normal; a quiet *tenant* is the signal. The tick
    ;; judges every key's last event together, so one busy key speaks for
    ;; the whole organization.
    (it "is silent when no key has seen an event inside the threshold"
      (should= [{:kind :silent :tenant :tonotop :silent-hours 8}]
               (evaluate {:tenants [{:tenant :tonotop :keys ["spaces/ENG" "spaces/OPS"]}]
                          :state   {:last-event-at {"spaces/ENG" "2026-09-18T04:00:00Z"
                                                    "spaces/OPS" "2026-09-17T04:00:00Z"}
                                    :door-last-hit "2026-09-18T11:00:00Z"}
                          :remote  {"spaces/ENG" {:expires-at "2026-09-25T12:00:00Z"}}})))

    (it "is not silent when one key is quiet for days and another spoke an hour ago"
      (should= []
               (evaluate {:tenants [{:tenant :tonotop :keys ["spaces/ENG" "spaces/OPS"]}]
                          :state   {:last-event-at {"spaces/ENG" "2026-09-14T04:00:00Z"
                                                    "spaces/OPS" "2026-09-18T11:00:00Z"}
                                    :door-last-hit "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "says nothing about an organization that has never seen an event"
      (should= []
               (evaluate {:tenants [{:tenant :tonotop :keys ["spaces/ENG"]}]
                          :state   {:door-last-hit "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "judges each organization by its own threshold"
      (should= [{:kind :silent :tenant :acme :silent-hours 2}]
               (evaluate {:config  {:google {:tonotop {:health {:silent-after-hours 6}}
                                             :acme    {:health {:silent-after-hours 1}}}}
                          :tenants [{:tenant :acme :keys ["spaces/OPS"]}
                                    {:tenant :tonotop :keys ["spaces/ENG"]}]
                          :state   {:last-event-at {"spaces/OPS" "2026-09-18T10:00:00Z"
                                                    "spaces/ENG" "2026-09-18T10:00:00Z"}
                                    :door-last-hit "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    ;; One registration can cover a whole workspace (`spaces/-`) while events
    ;; arrive under concrete space ids, so what counts is every key the health
    ;; state has heard from — the caller passes those in.
    (it "counts a space that spoke even when no registration is keyed on it"
      (should= []
               (evaluate {:tenants [{:tenant :tonotop :keys ["spaces/-" "spaces/AAA"]}]
                          :state   {:last-event-at {"spaces/AAA" "2026-09-18T11:59:00Z"}
                                    :door-last-hit "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    ;; Nobody spoke, but the organization's own heartbeat came back: it is
    ;; reachable, which is what silence asks about (isaac-an14).
    (it "counts a heartbeat that came back as having heard from the organization"
      (should= []
               (evaluate {:tenants [{:tenant :tonotop :keys ["spaces/ENG"]}]
                          :state   {:last-event-at     {"spaces/ENG" "2026-09-14T04:00:00Z"}
                                    :last-heartbeat-at {:tonotop "2026-09-18T11:00:00Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "is silent when neither an event nor a heartbeat has arrived inside the threshold"
      (should= [{:kind :silent :tenant :tonotop :silent-hours 8}]
               (evaluate {:config  {:google {:tonotop {:health {:silent-after-hours 6
                                                                :heartbeat {:enabled false}}}}}
                          :tenants [{:tenant :tonotop :keys ["spaces/ENG"]}]
                          :state   {:last-event-at     {"spaces/ENG" "2026-09-14T04:00:00Z"}
                                    :last-heartbeat-at {:tonotop "2026-09-18T04:00:00Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "defaults the threshold to 6 hours"
      (should= 6 (sut/silent-hours-cfg {:google {:tonotop {}}} :tonotop))))

  (context "expiry stays per key"

    (it "flags expiry when Google expiry is in the past"
      (should= [{:kind :expired :key "spaces/ENG"}]
               (evaluate {:tenants [{:tenant :tonotop :keys ["spaces/ENG"]}]
                          :state   {:last-event-at {"spaces/ENG" "2026-09-18T11:30:00Z"}
                                    :door-last-hit "2026-09-18T11:00:00Z"}
                          :remote  {"spaces/ENG" {:expires-at "2026-09-18T09:00:00Z"}}}))))

  (context "the door"

    (it "flags an unreached door when the door is up and door-last-hit is missing"
      (should= [{:kind :door-unreached}]
               (evaluate {:tenants  [{:tenant :tonotop :keys ["spaces/ENG"]}]
                          :door-up? true
                          :state    {:last-event-at {"spaces/ENG" "2026-09-18T04:00:00Z"}}
                          :remote   {"spaces/ENG" {:expires-at "2026-09-25T12:00:00Z"}}})))

    (it "does not flag the door when the server is not up"
      (should= []
               (evaluate {:tenants [{:tenant :tonotop :keys ["spaces/ENG"]}]
                          :state   {:last-event-at {"spaces/ENG" "2026-09-18T11:30:00Z"}}
                          :remote  {"spaces/ENG" {:expires-at "2026-09-25T12:00:00Z"}}}))))

  (context "the synthetic heartbeat (isaac-an14)"

    (it "is missed when the published heartbeat is older than the deadline and nothing arrived"
      (should= [{:kind :heartbeat-missed :tenant :tonotop :deadline-ms 60000}]
               (evaluate {:tenants [{:tenant :tonotop :keys []}]
                          :state   {:heartbeat-sent-at {:tonotop "2026-09-18T11:58:00Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "is not missed while the heartbeat is still inside the deadline"
      (should= []
               (evaluate {:now     (Instant/parse "2026-09-18T11:58:30Z")
                          :tenants [{:tenant :tonotop :keys []}]
                          :state   {:heartbeat-sent-at {:tonotop "2026-09-18T11:58:00Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "is not missed when the heartbeat arrived after it was published"
      (should= []
               (evaluate {:tenants [{:tenant :tonotop :keys []}]
                          :state   {:heartbeat-sent-at {:tonotop "2026-09-18T11:58:00Z"}
                                    :last-heartbeat-at {:tonotop "2026-09-18T11:58:02Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "is missed again when a later heartbeat goes unanswered"
      (should= [{:kind :heartbeat-missed :tenant :tonotop :deadline-ms 60000}]
               (evaluate {:tenants [{:tenant :tonotop :keys []}]
                          :state   {:heartbeat-sent-at {:tonotop "2026-09-18T11:58:00Z"}
                                    :last-heartbeat-at {:tonotop "2026-09-18T10:00:00Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "says nothing before the first heartbeat is published"
      (should= []
               (evaluate {:tenants [{:tenant :tonotop :keys []}]
                          :state   {:door-last-hit "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "says nothing for an organization that turned the heartbeat off"
      (should= []
               (evaluate {:config  {:google {:tonotop {:health {:heartbeat {:enabled false}}}}}
                          :tenants [{:tenant :tonotop :keys []}]
                          :state   {:heartbeat-sent-at {:tonotop "2026-09-18T10:00:00Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "honors an organization's own deadline"
      (should= []
               (evaluate {:config  {:google {:tonotop {:health {:heartbeat {:deadline-ms 600000}}}}}
                          :tenants [{:tenant :tonotop :keys []}]
                          :state   {:heartbeat-sent-at {:tonotop "2026-09-18T11:58:00Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "is on unless an organization says otherwise, at a 60s deadline"
      (should (sut/heartbeat-enabled? {:google {:tonotop {}}} :tonotop))
      (should= 60000 (sut/heartbeat-deadline-ms {:google {:tonotop {}}} :tonotop))))

  (context "notification state"

    (it "does not re-notify a condition already notified and still present"
      (should-not (sut/should-notify?
                    {:kind :silent :tenant :tonotop}
                    {:notified-at {[:silent :tonotop] "2026-09-18T11:00:00Z"}})))

    (it "notifies a condition that has not been posted yet"
      (should (sut/should-notify?
                {:kind :silent :tenant :tonotop}
                {:notified-at {}}))))

  (context "logging once on transition (isaac-an14)"

    (it "logs a condition the first time it is seen"
      (log/capture-logs
        (sut/log-conditions! [{:kind :silent :tenant :tonotop :silent-hours 8}] {}))
      (should= [:google/silent] (mapv :event @log/captured-logs))
      (should= :tonotop (:tenant (first @log/captured-logs))))

    (it "says nothing on the next tick while the condition is still present"
      (log/capture-logs
        (sut/log-conditions! [{:kind :silent :tenant :tonotop :silent-hours 9}]
                             {:notified-at {[:silent :tonotop] "2026-09-18T11:00:00Z"}}))
      (should= [] @log/captured-logs))

    (it "logs the clear once when a notified condition is gone"
      (log/capture-logs
        (sut/log-conditions! [] {:notified-at {[:silent :tonotop] "2026-09-18T11:00:00Z"}}))
      (should= [:google/health-cleared :google/health-ok] (mapv :event @log/captured-logs))
      (should= :silent (:kind (first @log/captured-logs)))
      (should= :tonotop (:subject (first @log/captured-logs))))

    (it "logs a heartbeat miss and its clear once each"
      (log/capture-logs
        (sut/log-conditions! [{:kind :heartbeat-missed :tenant :tonotop :deadline-ms 60000}] {})
        (sut/log-conditions! [{:kind :heartbeat-missed :tenant :tonotop :deadline-ms 60000}]
                             {:notified-at {[:heartbeat-missed :tonotop] "2026-09-18T11:00:00Z"}})
        (sut/log-conditions! [] {:notified-at {[:heartbeat-missed :tonotop] "2026-09-18T11:00:00Z"}}))
      (should= [:google/heartbeat-missed :google/health-cleared :google/health-ok]
               (mapv :event @log/captured-logs))))

  (it "renders a status table with key expiry last-event and door"
    (should= ["spaces/ENG  2026-09-25T12:00:00Z  2026-09-18T11:30:00Z  door: never"]
             (sut/status-lines
               {:keys   ["spaces/ENG"]
                :remote {"spaces/ENG" {:expires-at "2026-09-25T12:00:00Z"}}
                :state  {:last-event-at {"spaces/ENG" "2026-09-18T11:30:00Z"}}})))

  )
