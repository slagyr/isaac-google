(ns isaac.google.pubsub-spec
  (:require
    [cheshire.core :as json]
    [isaac.google.events :as events]
    [isaac.google.pubsub :as sut]
    [isaac.google.tenants :as tenants]
    [speclj.core :refer :all])
  (:import (java.util Base64)))

(def config
  {:google {:tonotop {:topic "projects/marigold/topics/isaac"}
            :acme    {}}})

(defn- decode [encoded]
  (json/parse-string (String. (.decode (Base64/getDecoder) (str encoded)) "UTF-8") true))

(describe "publishing to an organization's Pub/Sub topic"

  (it "posts one message to the organization's own topic"
    (let [requests (atom [])]
      (with-redefs [events/request! (fn [req]
                                      (swap! requests conj req)
                                      {:messageIds ["4242"]})]
        (should= {:message-id "4242"}
                 (sut/publish! config :tonotop {:data       {:hello "marigold"}
                                                :attributes {"ce-type" "isaac.google/probe"}})))
      (let [{:keys [method url body]} (first @requests)
            message (first (:messages body))]
        (should= :post method)
        (should= "https://pubsub.googleapis.com/v1/projects/marigold/topics/isaac:publish" url)
        (should= {:hello "marigold"} (decode (:data message)))
        (should= {"ce-type" "isaac.google/probe"} (:attributes message)))))

  ;; The topic lives in the organization's own project and the token that
  ;; reaches it is that organization's, so the publish runs as that tenant.
  (it "publishes as the organization it is publishing for"
    (let [seen (atom nil)]
      (with-redefs [events/request! (fn [_] (reset! seen tenants/*tenant*) {:messageIds ["1"]})]
        (sut/publish! config :tonotop {:data {}}))
      (should= :tonotop @seen)))

  (it "says so when the organization configured no topic"
    (should-contain "no google.acme.topic configured"
                    (:error (sut/publish! config :acme {:data {}}))))

  (it "reports Google's refusal rather than throwing"
    (with-redefs [events/request! (fn [_] {:error :api-error :status 403 :message "no publish permission"})]
      (should= {:error "no publish permission"} (sut/publish! config :tonotop {:data {}}))))

  (it "reports a thrown failure as an error"
    (with-redefs [events/request! (fn [_] (throw (ex-info "connection reset" {})))]
      (should= {:error "connection reset"} (sut/publish! config :tonotop {:data {}}))))

  )
