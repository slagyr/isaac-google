(ns isaac.google.http
  "POST /google/pubsub — persist then 204. Processing is the inbox worker."
  (:require
    [cheshire.core :as json]
    [isaac.google.inbox :as inbox]
    [isaac.google.push :as push]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(defn- read-body [request]
  (let [body (:body request)]
    (cond
      (nil? body) {}
      (string? body) (json/parse-string body true)
      (map? body) body
      :else (json/parse-string (slurp body) true))))

(defn handler [request]
  (let [root  (or (nexus/get :root) (get-in request [:isaac/root]))
        event (push/unwrap (read-body request))]
    (inbox/accept! root event)
    (log/info :google/push-received
              :principal (or (get-in request [:isaac/principal :name]) :google-pubsub)
              :message-id (:message-id event)
              :type (:type event))
    {:status 204 :headers {} :body ""}))
