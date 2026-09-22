(ns isaac.google.component
  "Runtime :isaac/component — the registration timer and the inbox worker on
   the shared scheduler. The door persists a push and answers 204; the worker
   is what hands persisted events to the handlers, so without this task an
   accepted push sits in inbox/pending forever.

   Start is also where the push door's per-tenant trust rules are registered:
   the manifest can declare only the flat rule, because a rule's config refs
   are static paths, so a host serving several Google organizations gets one
   rule per tenant here. Registering the door is separate from scheduling the
   timers — a host that starts no background services still answers pushes
   (isaac-1zkz)."
  (:require
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.config.loader :as loader]
    [isaac.google.door :as door]
    [isaac.google.registration :as registration]
    [isaac.google.worker :as worker]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]))

(def default-tick-ms
  "The registration tick is hourly. What it does is slow work: renewals are
   judged in hours (`renew-within-hours`, default 24, so an hourly tick has
   two dozen chances before the shortest window closes) and health is a
   heartbeat rather than a chatter monitor. A 30-second tick bought nothing
   and cost a per-space warning every 30 seconds (isaac-an14)."
  3600000)
(def default-inbox-ms 2000)

(defn- live-config []
  (or (loader/snapshot "google component") {}))

(defn register-door!
  "Register one push-door trust rule per configured organization. `config` and
   `register!` default to live config and isaac-http's identity seam."
  [config register!]
  (let [cfg (or config (live-config))]
    (door/register-trust-rules! cfg (or register! (door/registrar)))))

(defn start!
  [{:keys [tick-ms inbox-ms config register-identity!]
    :or   {tick-ms default-tick-ms inbox-ms default-inbox-ms}}]
  (let [shared (or (nexus/get :scheduler)
                   (throw (ex-info "google registration requires :scheduler in isaac.nexus" {})))]
    (register-door! config register-identity!)
    (scheduler/schedule! shared
                         {:id      :google/registration
                          :trigger {:kind :interval :ms tick-ms}
                          :handler (fn [_] (registration/tick! {}))})
    (scheduler/schedule! shared
                         {:id      :google/inbox
                          :trigger {:kind :interval :ms inbox-ms}
                          :handler (fn [_] (worker/tick!))})
    {:scheduler shared
     :task-ids  [:google/registration :google/inbox]}))

(defn stop! [{:keys [scheduler task-ids task-id]}]
  (when scheduler
    (doseq [id (or task-ids (when task-id [task-id]))]
      (scheduler/cancel! scheduler id))))

(defrecord RegistrationTimer [opts handle]
  component/Component
  (start [this]
    (register-door! nil nil)
    (when (and (nexus/get :scheduler)
               (not (false? (:start-background-services? opts))))
      (reset! handle (start! {})))
    this)
  (stop [this]
    (when-let [running @handle]
      (stop! running)
      (reset! handle nil))
    this))

(defmethod component-factory/create :google-registration
  [_ {:keys [opts]}]
  (->RegistrationTimer opts (atom nil)))
