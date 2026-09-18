(ns isaac.google.push
  "Unwrap a Pub/Sub push envelope into an inbox event."
  (:require
    [cheshire.core :as json])
  (:import
    (java.util Base64)))

(defn- decode-data [encoded]
  (when (seq encoded)
    (let [bytes (.decode (Base64/getDecoder) (str encoded))
          text  (String. bytes "UTF-8")]
      (try
        (json/parse-string text true)
        (catch Exception _
          text)))))

(defn- event-type [attributes data]
  (or (get attributes "ce-type")
      (get attributes :ce-type)
      (when (or (:emailAddress data) (:historyId data)
                (get data "emailAddress") (get data "historyId"))
        "gmail/watch")))

(defn unwrap
  "Turn a Pub/Sub push JSON body into {:message-id :type :data :publish-time}."
  [body]
  (let [message     (or (:message body) (get body "message"))
        message-id  (or (:messageId message) (get message "messageId"))
        publish     (or (:publishTime message) (get message "publishTime"))
        attributes  (or (:attributes message) (get message "attributes") {})
        data        (decode-data (or (:data message) (get message "data")))]
    {:message-id   message-id
     :type         (event-type attributes data)
     :data         data
     :publish-time publish}))
