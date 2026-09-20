(ns isaac.google.worker-spec
  (:require
    [isaac.fs :as fs]
    [isaac.google.handler :as handler]
    [isaac.google.inbox :as inbox]
    [isaac.google.tenants :as tenants]
    [isaac.google.worker :as sut]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ROOT "/test/isaac")

(defn- accept! [event]
  (inbox/accept! ROOT event))

(describe "google inbox worker"

  (around [example]
    (nexus/-with-nexus {:root ROOT :fs (fs/mem-fs)}
      (handler/reset-handlers!)
      (log/capture-logs (example))))

  (it "hands a pending event to the handler for its type"
    (let [seen (atom [])]
      (handler/register-handler! ["chat/created" (fn [event] (swap! seen conj (:message-id event)))])
      (accept! {:message-id "m-1" :type "chat/created" :data {}})
      (sut/tick!)
      (should= ["m-1"] @seen)
      (should (fs/exists? (fs/instance) (str ROOT "/google/inbox/done/m-1.edn")))))

  ;; The handler asks for "the token" and must get the token of the
  ;; organization that sent the push, without being told which (isaac-1zkz).
  (it "acts as the tenant the door stamped on the event"
    (let [seen (atom [])]
      (handler/register-handler! ["chat/created" (fn [_] (swap! seen conj tenants/*tenant*))])
      (accept! {:message-id "m-2" :type "chat/created" :tenant :acme :data {}})
      (sut/tick!)
      (should= [:acme] @seen)))

  (it "acts as the default tenant for an event with none"
    (let [seen (atom [])]
      (handler/register-handler! ["chat/created" (fn [_] (swap! seen conj tenants/*tenant*))])
      (accept! {:message-id "m-3" :type "chat/created" :data {}})
      (sut/tick!)
      (should= [:default] @seen)))

  (it "leaves the thread's tenant unbound after the handler returns"
    (handler/register-handler! ["chat/created" (fn [_] nil)])
    (accept! {:message-id "m-4" :type "chat/created" :tenant :acme :data {}})
    (sut/tick!)
    (should-be-nil tenants/*tenant*))
  )
