(ns isaac.google.component
  "Runtime :isaac/component — registration timer on the shared scheduler."
  (:require
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.google.registration :as registration]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]))

(def default-tick-ms 30000)

(defn start!
  [{:keys [tick-ms] :or {tick-ms default-tick-ms}}]
  (let [shared (or (nexus/get :scheduler)
                   (throw (ex-info "google registration requires :scheduler in isaac.nexus" {})))]
    (scheduler/schedule! shared
                         {:id      :google/registration
                          :trigger {:kind :interval :ms tick-ms}
                          :handler (fn [_] (registration/tick! {}))})
    {:scheduler shared
     :task-id   :google/registration}))

(defn stop! [{:keys [scheduler task-id]}]
  (when scheduler
    (scheduler/cancel! scheduler task-id)))

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
