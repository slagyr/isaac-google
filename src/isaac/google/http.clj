(ns isaac.google.http
  "POST /google/pubsub — persist then 204. Processing is the inbox worker."
  (:require
    [cheshire.core :as json]
    [isaac.google.health :as health]
    [isaac.google.inbox :as inbox]
    [isaac.google.push :as push]
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

(defn handler [request]
  (let [root  (or (nexus/get :root) (get-in request [:isaac/root]))
        event (push/unwrap (read-body request))
        now   (or (memory/now) (Instant/now))]
    (when root
      (health/record-door-hit! root now)
      (when-let [k (or (get-in event [:data :space])
                       (get-in event [:data :spaceName])
                       (some-> (get-in event [:data :message :name])
                               (as-> n (second (re-find #"^(spaces/[^/]+)" n)))))]
        (health/record-last-event! root k now)))
    (inbox/accept! root event)
    (log/info :google/push-received
              :principal (or (get-in request [:isaac/principal :name]) :google-pubsub)
              :message-id (:message-id event)
              :type (:type event))
    {:status 204 :headers {} :body ""}))
