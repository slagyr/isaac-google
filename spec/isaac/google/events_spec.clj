(ns isaac.google.events-spec
  (:require
    [isaac.google.events :as sut]
    [speclj.core :refer :all]))

(describe "Workspace Events"

  (it "hands back the subscription inside a finished create Operation, not the operation"
    (with-redefs [sut/request! (fn [_] {:name "operations/abc" :done true
                                        :response {:name "subscriptions/s-1" :expireTime "2026-09-26T00:00:00Z"
                                                   :targetResource "//chat.googleapis.com/spaces/ENG"}})]
      (let [r (sut/create-subscription! {:targetResource "//chat.googleapis.com/spaces/ENG"})]
        (should= "subscriptions/s-1" (:name r))
        (should= "2026-09-26T00:00:00Z" (:expireTime r)))))

  (it "passes a plain subscription (or an error result) through untouched"
    (with-redefs [sut/request! (fn [_] {:name "subscriptions/s-2" :expireTime "2026-09-26T00:00:00Z"})]
      (should= "subscriptions/s-2" (:name (sut/create-subscription! {}))))
    (with-redefs [sut/request! (fn [_] {:error true :status 403 :message "no"})]
      (should= 403 (:status (sut/create-subscription! {})))))
  
  (it "lists subscriptions with the event-type filter Google requires"
    (let [seen (atom nil)]
      (with-redefs [sut/request! (fn [req] (reset! seen req) {:subscriptions []})]
        (sut/list-subscriptions!)
        (should= "event_types:\"google.workspace.chat.message.v1.created\"" (get-in @seen [:query :filter])))))
  )
