(ns isaac.google.pubsub-spec
  (:require
    [cheshire.core :as json]
    [isaac.google.events :as events]
    [isaac.google.pubsub :as sut]
    [isaac.google.service-account :as service-account]
    [isaac.google.tenants :as tenants]
    [speclj.core :refer :all])
  (:import (java.util Base64)))

(def config
  {:google {:tonotop {:topic  "projects/marigold/topics/isaac"
                      :pubsub {:credentials-file "google/pubsub-sa.json"}}
            :acme    {}}})

(def sa-headers
  {"Authorization" "Bearer sa-at-1" "Content-Type" "application/json"})

(defn- with-service-account [f]
  (with-redefs [service-account/auth-headers (fn [_ _] {:headers sa-headers})]
    (f)))

(defn- decode [encoded]
  (json/parse-string (String. (.decode (Base64/getDecoder) (str encoded)) "UTF-8") true))

(describe "publishing to an organization's Pub/Sub topic"

  ;; Every publish carries the service account's token. The exchange itself is
  ;; isaac.google.service-account's business and has its own spec; here it is
  ;; stubbed so what reaches the wire is what this namespace decided.
  (around [it] (with-service-account it))

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

  ;; Pub/Sub is infrastructure. Publishing as the signed-in person is what put
  ;; the whole Google grant under a Workspace Cloud reauthentication clock and
  ;; killed the refresh token every ~15 hours (isaac-ey6q, isaac-286x).
  (it "authenticates as the organization's service account, not as the signed-in user"
    (let [requests (atom [])]
      (with-redefs [events/request! (fn [req] (swap! requests conj req) {:messageIds ["1"]})]
        (sut/publish! config :tonotop {:data {}}))
      (should= sa-headers (:headers (first @requests)))))

  (it "asks the service account for the organization it is publishing for"
    (let [asked (atom nil)]
      (with-redefs [service-account/auth-headers (fn [_ id] (reset! asked id) {:headers sa-headers})
                    events/request!              (fn [_] {:messageIds ["1"]})]
        (sut/publish! config :tonotop {:data {}}))
      (should= :tonotop @asked)))

  ;; The config check refuses this at startup; if one ever reaches here it is
  ;; evidence, not an exception to unwind through a timer.
  (it "says which config key is missing when the organization has no service account"
    (let [reached (atom false)]
      (with-redefs [service-account/auth-headers (fn [_ _] {:error "google.tonotop.pubsub.credentials-file must name a service-account JSON key"})
                    events/request!              (fn [_] (reset! reached true) {:messageIds ["1"]})]
        (should-contain "google.tonotop.pubsub.credentials-file"
                        (:error (sut/publish! config :tonotop {:data {}})))
        (should= false @reached))))

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
