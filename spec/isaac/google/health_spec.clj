(ns isaac.google.health-spec
  (:require
    [isaac.google.health :as sut]
    [isaac.logger :as log]
    [speclj.core :refer :all])
  (:import (java.time Instant)))

(def now (Instant/parse "2026-09-18T12:00:00Z"))

(def config
  {:google {:tonotop {:health {:silent-after-hours 6}}}})

(def watching-tenant
  "An organization that expects a heartbeat every hour. Naming the interval is
   what turns the watch on: nothing else could say what late means."
  {:health {:heartbeat {:expected-interval-ms 3600000}}})

(def watching
  {:google {:tonotop watching-tenant}})

(defn- heartbeat-errors [config]
  (:errors (sut/check-heartbeat {:config config})))

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
               (evaluate {:config  {:google {:tonotop {:health {:silent-after-hours 6}}}}
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

  (context "the heartbeat, published from outside Isaac (isaac-clly)"

    ;; Isaac publishes nothing. Cloud Scheduler does, on a schedule, as a
    ;; Google APIs service account inside GCP — so there is no send to pair an
    ;; arrival with, and the only evidence is arrival recency against the
    ;; interval the organization says to expect one on.

    (it "is quiet while a heartbeat has arrived inside the interval"
      (should= []
               (evaluate {:config  watching
                          :tenants [{:tenant :tonotop :keys []}]
                          :state   {:last-heartbeat-at {:tonotop "2026-09-18T11:30:00Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "is quiet while the last arrival is inside the interval plus grace"
      (should= []
               (evaluate {:config  watching
                          :tenants [{:tenant :tonotop :keys []}]
                          :state   {:last-heartbeat-at {:tonotop "2026-09-18T10:59:30Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "is missed once nothing has arrived for the interval plus grace"
      (should= [{:kind :heartbeat-missed :tenant :tonotop :deadline-ms 3660000}]
               (evaluate {:config  watching
                          :tenants [{:tenant :tonotop :keys []}]
                          :state   {:last-heartbeat-at {:tonotop "2026-09-18T10:55:00Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    ;; The regression this bean exists for. Paired against a send there was no
    ;; send, so elapsed was nil and the condition never fired: a watchdog that
    ;; read as enabled and could not bark. Never having seen one is the same
    ;; pipeline failure as having stopped seeing them.
    (it "is missed for an organization that has never seen a heartbeat at all"
      (should= [{:kind :heartbeat-missed :tenant :tonotop :deadline-ms 3660000}]
               (evaluate {:config  watching
                          :tenants [{:tenant :tonotop :keys []}]
                          :state   {:door-last-hit "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "honors an organization's own grace"
      (should= []
               (evaluate {:config  {:google {:tonotop {:health {:heartbeat {:expected-interval-ms 3600000
                                                                           :grace-ms             600000}}}}}
                          :tenants [{:tenant :tonotop :keys []}]
                          :state   {:last-heartbeat-at {:tonotop "2026-09-18T10:55:00Z"}
                                    :door-last-hit     "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "says nothing about an organization that watches for no heartbeat"
      (should= []
               (evaluate {:tenants [{:tenant :tonotop :keys []}]
                          :state   {:door-last-hit "2026-09-18T11:00:00Z"}
                          :remote  {}})))

    (it "watches only once an interval names what late means"
      (should-not (sut/heartbeat-watched? {:google {:tonotop {}}} :tonotop))
      (should (sut/heartbeat-watched? watching :tonotop)))

    (it "budgets the interval plus a 60s default grace"
      (should= 3660000 (sut/heartbeat-budget-ms watching :tonotop))))

  ;; There is exactly one way to be half-configured — an interval nobody set —
  ;; and it is the one shape that would read as watched and never fire. The
  ;; loader refuses it rather than starting a host with an inert watchdog
  ;; (isaac-clly).
  (context "refusing a heartbeat that could not fire (isaac-clly)"

    (it "passes an organization that watches for no heartbeat"
      (should= [] (heartbeat-errors {:google {:tonotop {:topic "projects/marigold/topics/isaac"}}})))

    (it "passes an organization that names its interval"
      (should= [] (heartbeat-errors watching)))

    (it "refuses a heartbeat configured with no interval"
      (let [errors (heartbeat-errors {:google {:tonotop {:health {:heartbeat {:grace-ms 60000}}}}})]
        (should= 1 (count errors))
        (should= "google.tonotop.health.heartbeat.expected-interval-ms" (:key (first errors)))
        (should-contain "no interval" (:value (first errors)))))

    (it "refuses an interval that is not a positive number of milliseconds"
      (let [errors (heartbeat-errors {:google {:tonotop {:health {:heartbeat {:expected-interval-ms 0}}}}})]
        (should= 1 (count errors))
        (should-contain "positive" (:value (first errors)))))

    (it "names each organization that is half-configured"
      (should= ["google.acme.health.heartbeat.expected-interval-ms"]
               (mapv :key (heartbeat-errors
                            {:google {:tonotop watching-tenant
                                      :acme    {:health {:heartbeat {:grace-ms 1000}}}}})))))

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
