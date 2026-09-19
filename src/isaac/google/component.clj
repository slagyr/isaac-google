(ns isaac.google.component
  "Runtime :isaac/component — the registration timer and the inbox worker on
   the shared scheduler. The door persists a push and answers 204; the worker
   is what hands persisted events to the handlers, so without this task an
   accepted push sits in inbox/pending forever."
  (:require
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.google.registration :as registration]
    [isaac.google.worker :as worker]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]))

(def default-tick-ms 30000)
(def default-inbox-ms 2000)

(defn start!
  [{:keys [tick-ms inbox-ms] :or {tick-ms default-tick-ms inbox-ms default-inbox-ms}}]
  (let [shared (or (nexus/get :scheduler)
                   (throw (ex-info "google registration requires :scheduler in isaac.nexus" {})))]
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
