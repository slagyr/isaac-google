(ns isaac.google.component-spec
  (:require
    [isaac.component.protocol :as component]
    [isaac.config.loader :as loader]
    [isaac.google.component :as sut]
    [isaac.google.door :as door]
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

  ;; One door, one trust rule per organization. A rule's config refs are static
  ;; paths that must name an organization, which the manifest cannot know, so
  ;; every rule is registered at boot (isaac-1zkz, isaac-okfj).
  (context "the door's trust rules (isaac-1zkz)"

    (it "registers one rule per organization at start"
      (let [registered (atom [])
            sched      (scheduler/create {})]
        (nexus/-with-nexus {:scheduler sched}
          (sut/start! {:config            {:google {:tonotop {:project "marigold"}
                                                    :acme    {:project "acme-prod"}}}
                       :register-identity! (fn [entry] (swap! registered conj (first entry)))}))
        (should= [:google-pubsub/acme :google-pubsub/tonotop] (sort @registered))
        (scheduler/shutdown! sched)))

    (it "registers a one-organization host's single rule, named after it"
      (let [registered (atom [])
            sched      (scheduler/create {})]
        (nexus/-with-nexus {:scheduler sched}
          (sut/start! {:config             {:google {:tonotop {:project "marigold"}}}
                       :register-identity! (fn [entry] (swap! registered conj (first entry)))}))
        (should= [:google-pubsub/tonotop] @registered)
        (scheduler/shutdown! sched)))

    ;; A host that starts no background services still answers pushes, so the
    ;; door's identity cannot ride on the scheduler.
    (it "registers the door at start even when background services are off"
      (let [registered (atom [])]
        (loader/set-snapshot! {:google {:acme {:project "acme-prod"}}} "component spec")
        (with-redefs [door/registrar (fn [] (fn [entry] (swap! registered conj (first entry))))]
          (component/start (sut/->RegistrationTimer {:start-background-services? false} (atom nil))))
        (should= [:google-pubsub/acme] @registered)))

    (it "takes the live config when no config is named"
      (let [registered (atom [])]
        (loader/set-snapshot! {:google {:tonotop {:project "marigold"}}} "component spec")
        (with-redefs [door/registrar (fn [] (fn [entry] (swap! registered conj (first entry))))]
          (sut/register-door! nil nil))
        (should= [:google-pubsub/tonotop] @registered))))
  )
