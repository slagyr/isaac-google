(ns isaac.google.registration-spec
  (:require
    [isaac.fs :as fs]
    [isaac.google.events]
    [isaac.google.health]
    [isaac.google.heartbeat]
    [isaac.google.registration :as sut]
    [isaac.google.tenants :as tenants]
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

  (context "an entry Google cannot list (:remote and :delete! hooks)"

    (with calls (atom []))
    ;; Every reconcile is one organization's: with none configured there is no
    ;; token to reach Google with, so there is nothing to reconcile
    ;; (isaac-okfj).
    (with cfg {:google {:tonotop {:project "p-tonotop"}}})
    (with entry (let [calls @calls]
                  {:create! (fn [k] (swap! calls conj [:create k])
                              {:name k :expiration "1790424000000"})
                   :renew!  (fn [n] (swap! calls conj [:renew n])
                              {:name n :expiration "1790424000000"})
                   :expiry  (fn [r] (some-> (:expiration r) parse-long Instant/ofEpochMilli))
                   :key     (fn [] ["yopp@tonotop.com"])
                   :remote  (fn [] (select-keys (sut/load-state root) ["yopp@tonotop.com"]))
                   :delete! (fn [n] (swap! calls conj [:stop n]))}))

    (around [example]
      (sut/reset-registrations!)
      (nexus/-with-nexus {:root root :fs (fs/mem-fs)}
        (with-redefs [isaac.google.events/list-subscriptions! (fn [] {:subscriptions []})
                      isaac.google.health/apply! (fn [_] nil)]
          (example)))
      (sut/reset-registrations!))

    (it "creates on the first tick, remembers the expiry, and does not create again"
      (sut/register! [:gmail-watch @entry])
      (sut/tick! {:now now :root root :config @cfg :door-up? true})
      (should= [[:create "yopp@tonotop.com"]] @@calls)
      (should= "2026-09-26T12:00:00Z" (get-in (sut/load-state root) ["yopp@tonotop.com" :expires-at]))
      (sut/tick! {:now now :root root :config @cfg :door-up? true})
      (should= [[:create "yopp@tonotop.com"]] @@calls))

    (it "renews through :renew! once the remembered expiry is inside the window"
      (sut/register! [:gmail-watch @entry])
      (sut/save-state! root {"yopp@tonotop.com" {:name "yopp@tonotop.com" :expires-at "2026-09-19T06:00:00Z"}})
      (sut/tick! {:now now :root root :config @cfg :door-up? true})
      (should= [[:renew "yopp@tonotop.com"]] @@calls))

    (it "ticks with no :door-up? in opts the way the scheduler calls it, deciding door state itself"
      (sut/register! [:gmail-watch @entry])
      (should-not-throw (sut/tick! {:root root :config @cfg}))
      (should= [[:create "yopp@tonotop.com"]] @@calls))

    (it "stops through :delete! when the key left config"
      (sut/register! [:gmail-watch (assoc @entry :key (fn [] []) :remote (fn [] (sut/load-state root)))])
      (sut/save-state! root {"yopp@tonotop.com" {:name "yopp@tonotop.com" :expires-at "2026-09-24T12:00:00Z"}})
      (sut/tick! {:now now :root root :config @cfg :door-up? true})
      (should= [[:stop "yopp@tonotop.com"]] @@calls)
      (should= {} (sut/load-state root)))

    ;; No organizations, no reconcile: there is no default to act as.
    (it "does nothing for a host that configured no organization"
      (sut/register! [:gmail-watch @entry])
      (sut/tick! {:now now :root root :config {} :door-up? true})
      (should= [] @@calls))
    )
  

  (context "several Google organizations"

    (with calls (atom []))
    (with cfg {:google {:tonotop {:project "p-tonotop" :renew-within-hours 24}
                        :acme    {:project "p-acme" :renew-within-hours 1}}})
    (with entry (let [calls @calls]
                  {:create! (fn [k]
                              (swap! calls conj [:create tenants/*tenant* k])
                              {:name k :expireTime "2026-09-25T12:00:00Z"})
                   :renew!  (fn [n]
                              (swap! calls conj [:renew tenants/*tenant* n])
                              {:name n :expireTime "2026-09-25T12:00:00Z"})
                   :key     (fn [] [(str "spaces/" (name (or tenants/*tenant* :none)))])
                   :delete! (fn [n] (swap! calls conj [:stop tenants/*tenant* n]))
                   :remote  (fn []
                              (select-keys (sut/load-state root)
                                           [(str "spaces/" (name (or tenants/*tenant* :none)))]))}))

    (around [example]
      (sut/reset-registrations!)
      (nexus/-with-nexus {:root root :fs (fs/mem-fs)}
        (with-redefs [isaac.google.events/list-subscriptions! (fn [] {:subscriptions []})
                      isaac.google.health/apply! (fn [_] nil)]
          (example)))
      (sut/reset-registrations!))

    (it "reconciles once per tenant, acting as that organization"
      (sut/register! [:chat @entry])
      (sut/tick! {:now now :root root :config @cfg :door-up? true})
      (should= [[:create :acme "spaces/acme"]
                [:create :tonotop "spaces/tonotop"]]
               @@calls))

    (it "renews inside each tenant's own window"
      (sut/register! [:chat @entry])
      (sut/save-state! root {"spaces/acme"    {:name "subscriptions/s-acme"
                                               :expires-at "2026-09-19T06:00:00Z"}
                             "spaces/tonotop" {:name "subscriptions/s-tonotop"
                                               :expires-at "2026-09-19T06:00:00Z"}})
      (sut/tick! {:now now :root root :config @cfg :door-up? true})
      (should= [[:renew :tonotop "subscriptions/s-tonotop"]] @@calls))

    ;; Health is judged per organization, so the tick hands it each
    ;; organization with the keys that organization owns, and sends each one
    ;; its heartbeat (isaac-an14).
    (it "judges health per organization and sends each one a heartbeat"
      (let [judged (atom nil)
            beats  (atom nil)]
        (sut/register! [:chat @entry])
        (with-redefs [isaac.google.health/evaluate    (fn [opts] (reset! judged opts) [])
                      isaac.google.heartbeat/send-all! (fn [opts] (reset! beats opts))]
          (sut/tick! {:now now :root root :config @cfg :door-up? true}))
        (should= [{:tenant :acme :keys ["spaces/acme"]}
                  {:tenant :tonotop :keys ["spaces/tonotop"]}]
                 (:tenants @judged))
        (should= [:acme :tonotop] (:tenants @beats))
        (should= now (:now @beats))))
    )

  ;; A registration can cover a whole workspace while events arrive under
  ;; concrete space ids, so health is handed every key the state has heard
  ;; from as well as the registered ones (isaac-an14, after isaac-ihuc).
  (context "what health is judged by"

    (with cfg {:google {:tonotop {:project "p-tonotop"}}})
    (with entry {:create! (fn [_] {:name "subscriptions/s-all" :expireTime "2026-09-25T12:00:00Z"})
                 :renew!  (fn [n] {:name n :expireTime "2026-09-25T12:00:00Z"})
                 :key     (fn [] ["spaces/-"])
                 :remote  (fn [] {"spaces/-" {:name "subscriptions/s-all"
                                              :expires-at "2026-09-25T12:00:00Z"}})})

    (around [example]
      (sut/reset-registrations!)
      (nexus/-with-nexus {:root root :fs (fs/mem-fs)}
        (with-redefs [isaac.google.events/list-subscriptions! (fn [] {:subscriptions []})
                      isaac.google.health/apply! (fn [_] nil)]
          (example)))
      (sut/reset-registrations!))

    (it "hands health every key the state has heard from, not only the registered one"
      (let [judged (atom nil)]
        (sut/register! [:chat @entry])
        (isaac.google.health/save-state! root {:last-event-at {"spaces/AAA" "2026-09-18T11:59:00Z"}})
        (with-redefs [isaac.google.health/evaluate     (fn [opts] (reset! judged opts) [])
                      isaac.google.heartbeat/send-all! (fn [_])]
          (sut/tick! {:now now :root root :config @cfg :door-up? true}))
        (should= [{:tenant :tonotop :keys ["spaces/-" "spaces/AAA"]}] (:tenants @judged))))
    )
  )
