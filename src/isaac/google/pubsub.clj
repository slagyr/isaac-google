(ns isaac.google.pubsub
  "Publish one message to a Google organization's own Pub/Sub topic.

   The same call serves two riders: the hourly synthetic heartbeat
   (isaac-an14) and `isaac google smoke --send-live` (isaac-mu1i). Both want
   the identical thing — one real message, on this organization's topic, with
   this organization's token — so there is one way to do it rather than two
   copies that drift."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.google.events :as events]
    [isaac.google.tenants :as tenants])
  (:import (java.util Base64)))

(defn- b64 [^String s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

(defn publish!
  "Publish `{:data … :attributes …}` to `id`'s topic, as `id`. Returns
   `{:message-id …}` or `{:error reason}` — a publish this module cannot make
   is evidence, not an exception to unwind through a timer."
  [config id {:keys [data attributes]}]
  (let [topic (:topic (tenants/tenant-config config id))]
    (if (str/blank? (str topic))
      {:error (str "no google." (name id) ".topic configured")}
      (try
        (binding [tenants/*tenant* id]
          (let [body   {:messages [(cond-> {:data (b64 (json/generate-string data))}
                                     attributes (assoc :attributes attributes))]}
                result (events/request! {:method :post
                                         :url    (str "https://pubsub.googleapis.com/v1/" topic ":publish")
                                         :body   body})]
            (if (:error result)
              {:error (or (:message result) (str (:status result)))}
              {:message-id (first (:messageIds result))})))
        (catch Exception e
          {:error (or (.getMessage e) (str e))})))))
