(ns isaac.google.smoke
  "Pass/fail decisions for `isaac google smoke` — a repeatable check run
   against an already-running server on a live host, before a Google-module
   version bump ships (isaac-mu1i). The first live day found six defects no
   green suite saw, because every suite drove timers by hand and stubbed
   Google from docs; this checks what the server's own scheduler and Google's
   own API actually did.

   Every `decide-*` fn below is pure — an evidence map in, a
   `{:check :status :evidence}` verdict out — so it is spec'd with fixtures
   and no network (spec/isaac/google/smoke_spec.clj). Gathering the evidence
   (the door probe, reading registration/inbox/health state) is the
   untestable half; it lives in isaac.google.cli, which
   calls these fns and prints their verdicts. See doc/rollout.md for the
   defect → check mapping and how to run it."
  (:require
    [clojure.string :as str]
    [isaac.google.registration :as registration]))

(def DEFAULT-INBOX-THRESHOLD 0)
(def DEFAULT-SILENT-THRESHOLD 0)
(def DOOR-PATH "/google/pubsub")

(def SEND-LIVE-RETIRED
  "What `--send-live` says now. Publishing is no longer Isaac's job, so this
   command has nothing to publish — and the check it used to run is being run
   continuously by something better. A Cloud Scheduler job publishes the
   heartbeat to the topic as a Google APIs service account inside GCP: no
   exported key (which the organization's policy refuses to issue anyway), on
   a schedule rather than when somebody remembers, and originating outside
   the process under test. Its arrival is the same end-to-end proof — door,
   OIDC verification, inbox — and the `silent` check above reads it
   (isaac-clly)."
  (str "--send-live is retired: publishing is no longer Isaac's job. "
       "A Cloud Scheduler job publishes the heartbeat to the topic, and the "
       "silent check above fails when it stops arriving — the same end-to-end "
       "proof, run every interval instead of by hand. See doc/rollout.md."))

;; ---- verdicts -----------------------------------------------------------

(defn pass [check evidence]
  {:check check :status :pass :evidence evidence})

(defn fail [check evidence]
  {:check check :status :fail :evidence evidence})

(defn ok?
  "True for a passing verdict."
  [{:keys [status]}]
  (= :pass status))

(defn render-line
  "\"PASS <check> — <evidence>\" or \"FAIL <check> — <evidence>\"."
  [{:keys [check status evidence]}]
  (str (str/upper-case (name status)) " " (name check) " — " evidence))

;; ---- door: isaac-http answers, and (with :send-live) the OIDC verifier
;;      that once had no reflective key construction under bb actually
;;      verified a real Google-signed token --------------------------------

(defn decide-door
  "PASS when an unauthenticated push to the door is refused with 401 — the
   route is bound and isaac-http's identity layer is loaded and answering.
   A door that is unreachable, times out, or answers with anything but 401
   is the exposure defect the rollout runbook gates on before Funnel goes
   up."
  [{:keys [status error configured?]}]
  (cond
    (not configured?)
    (fail :door "no Google organization configured — nothing to smoke")

    error
    (fail :door (str "door unreachable: " error))

    (= 401 status)
    (pass :door "unauthenticated push refused with 401")

    :else
    (fail :door (str "expected 401 for an unauthenticated push, got " (pr-str status)))))

;; ---- registrations: the tick actually ran, and what it registered is
;;      real (isaac-6krg: tick! NPE, create returns an Operation, list
;;      needs Google's filter) -----------------------------------------

(defn- key-verdict [now renew-within-hours [k {:keys [expires-at]}]]
  (cond
    (nil? expires-at)
    {:key k :ok? false :reason (str k " not registered")}

    (registration/within-window? now expires-at 0)
    {:key k :ok? false :reason (str k " expired at " expires-at)}

    (registration/within-window? now expires-at renew-within-hours)
    {:key k :ok? false :reason (str k " expires " expires-at " — inside the "
                                    renew-within-hours "h renew window")}

    :else
    {:key k :ok? true :reason (str k " expires " expires-at)}))

(defn decide-registrations
  "PASS when every configured key (a Chat space subscription or the Gmail
   watch) has a live remote registration whose expiry is beyond the renew
   window — proof the registration tick ran, its create/renew calls landed
   on a real, listable Google resource (not an in-flight Operation), and
   Google's own listing was read with the filter it requires. `remote` is
   `{key {:expires-at ...}}`, the same shape `isaac google status` already
   assembles from the live Workspace Events listing merged with the Gmail
   watch's own persisted expiry."
  [{:keys [now keys remote renew-within-hours tenant]}]
  (let [renew-within-hours (or renew-within-hours registration/DEFAULT-RENEW-HOURS)
        prefix             (when tenant (str "tenant " (name tenant) ": "))]
    (if (empty? keys)
      (fail :registrations (str prefix "no Google keys configured — nothing registered to check"))
      (let [verdicts (mapv (partial key-verdict now renew-within-hours)
                           (map (fn [k] [k (get remote k)]) keys))
            bad      (remove :ok? verdicts)
            evidence (str prefix (str/join "; " (map :reason verdicts)))]
        (if (seq bad)
          (fail :registrations evidence)
          (pass :registrations evidence))))))

;; ---- inbox: the worker is scheduled and draining (isaac-ro67: the inbox
;;      worker was never scheduled in the server) --------------------------

(defn decide-inbox
  "PASS when the `inbox/pending` backlog is at or below the threshold. A
   scheduled, running worker drains pending records continuously; a worker
   that was never scheduled leaves them to accumulate forever, which is
   exactly what shipped once (isaac-ro67) and every timer-stepping suite
   missed.

   `unhandled` — records parked because no handler claims their ce-type — is
   reported alongside but never gates the verdict: an unhandled record is
   dealt with (parked, one warning already logged), not backlog piling up
   (isaac-pl8x)."
  [{:keys [pending unhandled threshold]}]
  (let [threshold (or threshold DEFAULT-INBOX-THRESHOLD)
        n         (count pending)
        u         (count unhandled)]
    (if (<= n threshold)
      (pass :inbox (str n " pending record(s), " u " unhandled, threshold " threshold))
      (fail :inbox (str n " pending record(s), " u " unhandled, exceeds threshold " threshold
                        (when-let [sample (first pending)]
                          (str "; e.g. message-id " (:message-id sample))))))))

;; ---- silent: the watchdog is not itself silently over budget ----------

(defn- silent-evidence [condition]
  (if (= :heartbeat-missed (:kind condition))
    (str (name (:tenant condition)) " heartbeat missed — no push within "
         (:deadline-ms condition) "ms")
    (str (name (:tenant condition)) " no event for " (:silent-hours condition) "h")))

(defn decide-silent
  "PASS when the number of currently-firing silence conditions is at or below
   the threshold. `conditions` is `isaac.google.health/evaluate`'s return
   value — the same pure decision the live registration tick already makes on
   every pass, so this reads the exact signal the server alerts on rather
   than a second, looser guess at staleness.

   Two conditions count: an organization with no event from Google inside its
   `silent-after-hours` (per tenant, not per key — a quiet space is normal),
   and a heartbeat that has not arrived inside the interval the organization
   expects one on. The second is the one that still speaks on a host nobody
   has messaged all day, and — since Cloud Scheduler publishes it, not Isaac
   — the one that took over what `--send-live` used to prove by hand
   (isaac-an14, isaac-clly)."
  [{:keys [conditions threshold]}]
  (let [threshold (or threshold DEFAULT-SILENT-THRESHOLD)
        silent    (filter #(#{:silent :heartbeat-missed} (:kind %)) conditions)
        n         (count silent)]
    (if (<= n threshold)
      (pass :silent (str n " silent condition(s), threshold " threshold))
      (fail :silent (str/join "; " (map silent-evidence silent))))))

;; ---- live push: gone. Isaac publishes nothing, so `smoke` has nothing to
;;      publish. The heartbeat Cloud Scheduler publishes is the same proof —
;;      a real Google-signed push through the real door into the real inbox —
;;      and `silent` above is the check that reads it (isaac-clly, retiring
;;      isaac-mu1i's --send-live and isaac-pl8x's probe handler).
