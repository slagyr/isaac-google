(ns isaac.google.component-spec
  (:require
    [isaac.google.component :as sut]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [speclj.core :refer :all]))

(describe "google runtime component"

  (it "schedules both the registration timer and the inbox worker, and cancels both on stop"
    (let [sched  (scheduler/create {})
          handle (nexus/-with-nexus {:scheduler sched}
                   (sut/start! {}))
          ids    (set (map :id (scheduler/list-tasks sched)))]
      (should-contain :google/registration ids)
      (should-contain :google/inbox ids)
      (sut/stop! handle)
      (should= #{} (set (map :id (scheduler/list-tasks sched))))
      (scheduler/shutdown! sched)))
  )
