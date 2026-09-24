(ns isaac.google.smoke-spec
  "Fixture-only specs for the pass/fail decisions `isaac google smoke` prints.
   Every fn here is pure — evidence map in, a verdict out — so these specs
   never touch a network or a filesystem. The evidence-gathering half (the
   HTTP probe, the inbox read) lives in isaac.google.cli and is not spec'd
   here; it needs a live host."
  (:require
    [isaac.google.smoke :as sut]
    [speclj.core :refer :all])
  (:import (java.time Instant)))

(def now (Instant/parse "2026-09-18T12:00:00Z"))

(describe "google smoke — door"

  (it "passes when an unauthenticated push is refused with 401"
    (should= {:check :door :status :pass :evidence "unauthenticated push refused with 401"}
             (sut/decide-door {:status 401 :configured? true})))

  (it "fails when nothing is configured to smoke"
    (should= :fail (:status (sut/decide-door {:status 401 :configured? false}))))

  (it "fails when the door cannot be reached at all"
    (let [result (sut/decide-door {:error "Connection refused" :configured? true})]
      (should= :fail (:status result))
      (should-contain "Connection refused" (:evidence result))))

  (it "fails when the door answers but does not refuse an unauthenticated push"
    (let [result (sut/decide-door {:status 204 :configured? true})]
      (should= :fail (:status result))
      (should-contain "204" (:evidence result))))
  )

(describe "google smoke — registrations (isaac-6krg: tick! NPE, create returns an Operation, list needs Google's filter)"

  (it "fails when no keys are configured"
    (should= :fail (:status (sut/decide-registrations {:now now :keys [] :remote {}}))))

  (it "fails when a configured key has no remote registration"
    (let [result (sut/decide-registrations {:now now :keys ["spaces/ENG"] :remote {}})]
      (should= :fail (:status result))
      (should-contain "spaces/ENG" (:evidence result))
      (should-contain "not registered" (:evidence result))))

  (it "fails when a registration already expired"
    (let [result (sut/decide-registrations
                   {:now                now
                    :keys               ["spaces/ENG"]
                    :remote             {"spaces/ENG" {:expires-at "2026-09-18T09:00:00Z"}}
                    :renew-within-hours 24})]
      (should= :fail (:status result))
      (should-contain "expired" (:evidence result))))

  (it "fails when a registration expires inside the renew window"
    (let [result (sut/decide-registrations
                   {:now                now
                    :keys               ["spaces/ENG"]
                    :remote             {"spaces/ENG" {:expires-at "2026-09-19T06:00:00Z"}}
                    :renew-within-hours 24})]
      (should= :fail (:status result))
      (should-contain "renew window" (:evidence result))))

  (it "passes when every configured key is registered beyond the renew window"
    (let [result (sut/decide-registrations
                   {:now                now
                    :keys               ["spaces/ENG"]
                    :remote             {"spaces/ENG" {:expires-at "2026-09-26T12:00:00Z"}}
                    :renew-within-hours 24})]
      (should= :pass (:status result))
      (should-contain "spaces/ENG" (:evidence result))))

  (it "labels evidence with the tenant on a multi-tenant host"
    (let [result (sut/decide-registrations
                   {:now now :keys [] :remote {} :tenant :marigold})]
      (should-contain "tenant marigold" (:evidence result))))
  )

(describe "google smoke — inbox (isaac-ro67: the inbox worker was never scheduled)"

  (it "passes when pending is empty"
    (should= {:check :inbox :status :pass :evidence "0 pending record(s), 0 unhandled, threshold 0"}
             (sut/decide-inbox {:pending [] :threshold 0})))

  (it "passes when pending is at the threshold"
    (should= :pass (:status (sut/decide-inbox {:pending [{:message-id "m-1"}] :threshold 1}))))

  (it "fails when pending exceeds the threshold, naming the oldest record"
    (let [result (sut/decide-inbox {:pending [{:message-id "m-1"} {:message-id "m-2"}] :threshold 0})]
      (should= :fail (:status result))
      (should-contain "2 pending" (:evidence result))
      (should-contain "m-1" (:evidence result))))

  ;; Unhandled records (no handler for their ce-type, isaac-pl8x) are shown
  ;; but never gate the verdict — they are already parked, not backlog.
  (it "shows the unhandled count without failing on it"
    (let [result (sut/decide-inbox {:pending [] :unhandled [{:message-id "m-3"}] :threshold 0})]
      (should= :pass (:status result))
      (should-contain "1 unhandled" (:evidence result))))
  )

(describe "google smoke — silent (the per-tenant silence and heartbeat watchdogs)"

  (it "passes when nothing is firing"
    (should= {:check :silent :status :pass :evidence "0 silent condition(s), threshold 0"}
             (sut/decide-silent {:conditions [] :threshold 0})))

  (it "passes when conditions are at the threshold"
    (should= :pass (:status (sut/decide-silent
                              {:conditions [{:kind :silent :tenant :tonotop :silent-hours 8}]
                               :threshold  1}))))

  (it "fails and names each organization past the threshold"
    (let [result (sut/decide-silent
                   {:conditions [{:kind :silent :tenant :tonotop :silent-hours 8}]
                    :threshold  0})]
      (should= :fail (:status result))
      (should-contain "tonotop" (:evidence result))
      (should-contain "8h" (:evidence result))))

  ;; A missed heartbeat is the same watchdog: the pipeline is not delivering,
  ;; whether or not anyone has been talking (isaac-an14).
  (it "fails on a missed heartbeat"
    (let [result (sut/decide-silent
                   {:conditions [{:kind :heartbeat-missed :tenant :tonotop :deadline-ms 60000}]
                    :threshold  0})]
      (should= :fail (:status result))
      (should-contain "tonotop" (:evidence result))
      (should-contain "heartbeat" (:evidence result))))

  (it "ignores conditions that are neither"
    (should= :pass (:status (sut/decide-silent
                              {:conditions [{:kind :expired :key "spaces/ENG"}]
                               :threshold  0}))))
  )

(describe "google smoke — --send-live is retired (isaac-clly)"

  ;; Publishing is no longer Isaac's job. Cloud Scheduler publishes the
  ;; heartbeat as a Google APIs service account inside GCP, which is a better
  ;; live push than this one ever was: it originates outside the process being
  ;; tested, it runs on a schedule rather than when somebody remembers, and it
  ;; needs no exported key — the thing `constraints/iam.disableServiceAccount\
  ;; KeyCreation` refuses to issue. The `silent` check above reads its arrival.
  (it "says plainly that publishing is not Isaac's job any more"
    (should-contain "no longer" sut/SEND-LIVE-RETIRED)
    (should-contain "Cloud Scheduler" sut/SEND-LIVE-RETIRED)
    (should-contain "silent" sut/SEND-LIVE-RETIRED))

  (it "decides no live push — there is none to decide"
    (should-be-nil (resolve 'isaac.google.smoke/decide-live-push)))
  )

(describe "google smoke — rendering and the overall verdict"

  (it "renders PASS with the check name and evidence"
    (should= "PASS door — unauthenticated push refused with 401"
             (sut/render-line {:check :door :status :pass :evidence "unauthenticated push refused with 401"})))

  (it "renders FAIL the same way"
    (should= "FAIL inbox — 2 pending record(s) exceeds threshold 0"
             (sut/render-line {:check :inbox :status :fail :evidence "2 pending record(s) exceeds threshold 0"})))

  (it "ok? is true only for a passing result"
    (should (sut/ok? {:status :pass}))
    (should-not (sut/ok? {:status :fail})))
  )
