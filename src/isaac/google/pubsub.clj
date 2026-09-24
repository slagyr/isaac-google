(ns isaac.google.pubsub
  "Publish one message to a Google organization's own Pub/Sub topic.

   The same call serves two riders: the hourly synthetic heartbeat
   (isaac-an14) and `isaac google smoke --send-live` (isaac-mu1i). Both want
   the identical thing — one real message, on this organization's topic —
   so there is one way to do it rather than two copies that drift.

   It goes out as the organization's **service account**, not as the
   signed-in person. Publishing is machine work; asking a human to consent to
   it put the whole Google grant under a Workspace Cloud reauthentication
   clock and killed the refresh token every ~15 hours (isaac-ey6q,
   isaac-286x). See isaac.google.service-account."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.google.events :as events]
    [isaac.google.service-account :as service-account]
    [isaac.google.tenants :as tenants])
  (:import (java.util Base64)))

(defn- b64 [^String s]
  (.encodeToString (Base64/getEncoder) (.getBytes s "UTF-8")))

(defn publish!
  "Publish `{:data … :attributes …}` to `id`'s topic, as `id`'s service
   account. Returns `{:message-id …}` or `{:error reason}` — a publish this
   module cannot make is evidence, not an exception to unwind through a
   timer."
  [config id {:keys [data attributes]}]
  (let [topic (:topic (tenants/tenant-config config id))]
    (if (str/blank? (str topic))
      {:error (str "no google." (name id) ".topic configured")}
      (try
        (binding [tenants/*tenant* id]
          (let [{:keys [headers error]} (service-account/auth-headers config id)]
            (if error
              {:error error}
              (let [body   {:messages [(cond-> {:data (b64 (json/generate-string data))}
                                         attributes (assoc :attributes attributes))]}
                    result (events/request! {:method  :post
                                             :url     (str "https://pubsub.googleapis.com/v1/" topic ":publish")
                                             :headers headers
                                             :body    body})]
                (if (:error result)
                  {:error (or (:message result) (str (:status result)))}
                  {:message-id (first (:messageIds result))})))))
        (catch Exception e
          {:error (or (.getMessage e) (str e))})))))
