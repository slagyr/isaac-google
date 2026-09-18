(ns isaac.google.push-spec
  (:require
    [cheshire.core :as json]
    [isaac.google.push :as sut]
    [speclj.core :refer :all])
  (:import
    (java.util Base64)))

(defn- b64-json [m]
  (.encodeToString (Base64/getEncoder) (.getBytes (json/generate-string m) "UTF-8")))

(describe "unwrap pubsub envelope"

  (it "decodes Chat CloudEvents by ce-type"
    (let [data {:message {:name "spaces/AAA/messages/1"}}
          body {:message {:messageId   "m-1"
                          :publishTime "2026-09-18T12:00:00Z"
                          :data        (b64-json data)
                          :attributes  {"ce-type" "google.workspace.chat.message.v1.created"}}}]
      (should= {:message-id "m-1"
                :type       "google.workspace.chat.message.v1.created"
                :data       data
                :publish-time "2026-09-18T12:00:00Z"}
               (sut/unwrap body))))

  (it "routes a Gmail watch with no ce-type to :gmail/watch"
    (let [data {:emailAddress "yopp@tonotop.com" :historyId "12345"}
          body {:message {:messageId "m-7"
                          :data      (b64-json data)}}]
      (should= {:message-id "m-7"
                :type       "gmail/watch"
                :data       data
                :publish-time nil}
               (sut/unwrap body))))
  )
