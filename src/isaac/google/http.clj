(ns isaac.google.http
  "POST /google/pubsub — persist then 204. Processing is the inbox worker.

   One door serves every Google organization. Which one sent a push is told
   twice: by the project in the subscription name, and by the service account
   whose OIDC token the request proved. Both must agree, and the tenant they
   agree on is stamped on the persisted event so the worker acts as the right
   organization (isaac-1zkz).

   A host that configured no organization has no one to own a push, so the
   door refuses it rather than keeping an event nobody answers for — which is
   what the flat `:google {:project ...}` shape now is (isaac-okfj)."
  (:require
    [cheshire.core :as json]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.google.health :as health]
    [isaac.google.heartbeat :as heartbeat]
    [isaac.google.inbox :as inbox]
    [isaac.google.push :as push]
    [isaac.google.tenants :as tenants]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory])
  (:import (java.time Instant)))

(defn- read-body [request]
  (let [body (:body request)]
    (cond
      (nil? body) {}
      (string? body) (json/parse-string body true)
      (map? body) body
      :else (json/parse-string (slurp body) true))))

(defn- runtime-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- live-config [request root]
  (or (:isaac/config request)
      (let [snap (loader/snapshot "google push door")]
        (if (seq (tenants/tenants snap))
          snap
          (or (when root
                (:config (loader/load-config-result {:root root :fs (runtime-fs)})))
              snap
              {})))))

(defn- record-health! [root event now]
  (health/record-door-hit! root now)
  (when-let [k (or (get-in event [:data :space])
                   (get-in event [:data :spaceName])
                   (some-> (get-in event [:data :message :name])
                           (as-> n (second (re-find #"^(spaces/[^/]+)" n)))))]
    (health/record-last-event! root k now)))

(defn- accept-heartbeat!
  "The tick's own synthetic message: record that it came back and stop. It
   is not persisted, so no worker drains it, no handler sees it and no turn
   starts; and it is not a last-event, so it never quiets the silence watch
   it exists to complement (isaac-an14)."
  [root tenant event now]
  (when root
    (health/record-door-hit! root now)
    (health/record-heartbeat! root tenant now))
  (log/debug :google/heartbeat-received :tenant tenant :message-id (:message-id event))
  {:status 204 :headers {} :body ""})

(defn- refuse-no-organization [event principal]
  (log/warn :google/no-organization
            :principal principal
            :message-id (:message-id event)
            :message "no Google organization is configured; :google is a map of organization id to config")
  {:status 401 :headers {} :body ""})

(defn- refuse-mismatch [decision event principal]
  (log/warn :google/tenant-mismatch
            :principal principal
            :message-id (:message-id event)
            :subscription-tenant (:tenant decision)
            :principal-tenant (:principal-tenant decision))
  {:status 403 :headers {} :body ""})

(defn handler [request]
  (let [root      (or (nexus/get :root) (get-in request [:isaac/root]))
        event     (push/unwrap (read-body request))
        principal (get-in request [:isaac/principal :name])
        config    (live-config request root)
        decision  (tenants/tenant-of-push config event principal)]
    (cond
      (empty? (tenants/tenants config))
      (refuse-no-organization event principal)

      (:refused decision)
      (refuse-mismatch decision event principal)

      :else
      (let [tenant (:tenant decision)
            event  (assoc event :tenant tenant)
            now    (or (memory/now) (Instant/now))]
        (if (heartbeat/heartbeat? event)
          (accept-heartbeat! root tenant event now)
          (do
            (when root
              (record-health! root event now))
            (inbox/accept! root event)
            (log/info :google/push-received
                      :principal (or principal :google-pubsub)
                      :message-id (:message-id event)
                      :tenant tenant
                      :type (:type event))
            {:status 204 :headers {} :body ""}))))))
