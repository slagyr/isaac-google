(ns isaac.google.component-spec
  (:require
    [isaac.component.protocol :as component]
    [isaac.config.loader :as loader]
    [isaac.google.component :as sut]
    [isaac.google.door :as door]
    [isaac.google.registration]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [speclj.core :refer :all]))

(defn- entry-id
  "What the identity seam was handed: a trust rule arrives as [id rule], the
   callback's code verifier as the function itself."
  [entry]
  (if (fn? entry) (:name (meta entry)) (first entry)))

(describe "google runtime component"

  ;; The registration tick is a slow watch: renewals are judged in hours and
  ;; health is a heartbeat, so it runs hourly. The inbox worker keeps its own
  ;; fast cadence — that one is draining real pushes (isaac-an14).
  (it "ticks the registration timer hourly and the inbox worker in seconds"
    (should= 3600000 sut/default-tick-ms)
    (should= 2000 sut/default-inbox-ms))

  (it "schedules each timer on its own cadence"
    (let [sched  (scheduler/create {})
          handle (nexus/-with-nexus {:scheduler sched}
                   (sut/start! {}))
          tasks  (into {} (map (juxt :id #(get-in % [:trigger :ms])) (scheduler/list-tasks sched)))]
      (should= 3600000 (:google/registration tasks))
      (should= 2000 (:google/inbox tasks))
      (sut/stop! handle)
      (scheduler/shutdown! sched)))

  ;; An :interval trigger fires after a full period. A restart must not wait an
  ;; hour to reconcile subscriptions and send the first heartbeat, so start!
  ;; also queues one immediate tick (isaac-nsh1).
  (it "runs the registration tick once at boot, ahead of the hourly interval"
    (let [ticks  (atom 0)
          sched  (scheduler/create {})
          handle (with-redefs [isaac.google.registration/tick! (fn [_] (swap! ticks inc))]
                   (nexus/-with-nexus {:scheduler sched}
                     (sut/start! {})))
          boot   (first (filter #(= :google/registration-boot (:id %)) (scheduler/list-tasks sched)))]
      (should-not-be-nil boot)
      (should= :delay (get-in boot [:trigger :kind]))
      (with-redefs [isaac.google.registration/tick! (fn [_] (swap! ticks inc))]
        ((:handler boot) {}))
      (should= 1 @ticks)
      (sut/stop! handle)
      (should= #{} (set (map :id (scheduler/list-tasks sched))))
      (scheduler/shutdown! sched)))

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
                       :register-identity! (fn [entry] (swap! registered conj (entry-id entry)))}))
        (should= [:google/oauth-callback :google-pubsub/acme :google-pubsub/tonotop] (sort @registered))
        (scheduler/shutdown! sched)))

    (it "registers a one-organization host's single rule, named after it"
      (let [registered (atom [])
            sched      (scheduler/create {})]
        (nexus/-with-nexus {:scheduler sched}
          (sut/start! {:config             {:google {:tonotop {:project "marigold"}}}
                       :register-identity! (fn [entry] (swap! registered conj (entry-id entry)))}))
        (should= [:google-pubsub/tonotop :google/oauth-callback] @registered)
        (scheduler/shutdown! sched)))

    ;; A host that starts no background services still answers pushes, so the
    ;; door's identity cannot ride on the scheduler.
    (it "registers the door at start even when background services are off"
      (let [registered (atom [])]
        (loader/set-snapshot! {:google {:acme {:project "acme-prod"}}} "component spec")
        (with-redefs [door/registrar (fn [] (fn [entry] (swap! registered conj (entry-id entry))))]
          (component/start (sut/->RegistrationTimer {:start-background-services? false} (atom nil))))
        (should= [:google-pubsub/acme :google/oauth-callback] @registered)))

    (it "takes the live config when no config is named"
      (let [registered (atom [])]
        (loader/set-snapshot! {:google {:tonotop {:project "marigold"}}} "component spec")
        (with-redefs [door/registrar (fn [] (fn [entry] (swap! registered conj (entry-id entry))))]
          (sut/register-door! nil nil))
        (should= [:google-pubsub/tonotop :google/oauth-callback] @registered)))

    ;; The consent redirect is a browser with no credentials, so the callback
    ;; door opens itself — on that one path, with one scope (isaac-2abl).
    (it "opens the OAuth callback door alongside the push door"
      (let [registered (atom [])]
        (loader/set-snapshot! {:google {:tonotop {:project "marigold"}}} "component spec")
        (with-redefs [door/registrar (fn [] (fn [entry] (swap! registered conj entry)))]
          (sut/register-door! nil nil))
        (should-contain door/callback-verifier @registered)))

    (it "opens no door at all on a host that serves no Google organization"
      (let [registered (atom [])]
        (loader/set-snapshot! {} "component spec")
        (with-redefs [door/registrar (fn [] (fn [entry] (swap! registered conj entry)))]
          (sut/register-door! nil nil))
        (should= [] @registered))))
  )
