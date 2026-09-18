(ns isaac.google.identity-spec
  (:require
    [cheshire.core :as json]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.google.identity :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all])
  (:import
    (java.util Base64)))

(defn- b64url [s]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) (.getBytes s "UTF-8")))

(defn- mint [claims]
  (str (b64url "{\"alg\":\"none\"}") "." (b64url (json/generate-string claims)) "."))

(def push-cfg
  {:google {:push {:endpoint        "https://isaac.example/google/pubsub"
                   :service-account "pubsub-push@marigold.iam.gserviceaccount.com"}}})

(defn- request [token]
  {:headers {"authorization" (str "Bearer " token)}})

(describe "Pub/Sub push identity verifier"

  (around [it]
    (reset! sut/skip-signature?* true)
    (try
      (with-redefs [loader/snapshot (fn [_] push-cfg)]
        (it))
      (finally
        (reset! sut/skip-signature?* false))))

  (it "yields google-pubsub with :google/push when aud and email match the door"
    (should= {:name :google-pubsub :scopes #{:google/push}}
             (sut/verify (request (mint {:aud   "https://isaac.example/google/pubsub"
                                         :email "pubsub-push@marigold.iam.gserviceaccount.com"})))))

  (it "rejects a token whose audience is not the configured door"
    (should= nil
             (sut/verify (request (mint {:aud   "https://elsewhere.example/hook"
                                         :email "pubsub-push@marigold.iam.gserviceaccount.com"})))))

  (it "rejects a token from the wrong service account"
    (should= nil
             (sut/verify (request (mint {:aud   "https://isaac.example/google/pubsub"
                                         :email "someone-else@marigold.iam.gserviceaccount.com"})))))

  (it "rejects an unsigned garbage bearer"
    (should= nil
             (sut/verify (request "not-a-jwt"))))

  (it "rejects when push config is missing (fail closed)"
    (with-redefs [loader/snapshot (fn [_] {})]
      (should= nil
               (sut/verify (request (mint {:aud   "https://isaac.example/google/pubsub"
                                           :email "pubsub-push@marigold.iam.gserviceaccount.com"}))))))

  (it "reads push config from the isaac root when snapshot is empty"
    (let [fs*  (fs/mem-fs)
          root "/test/isaac"]
      (fs/mkdirs fs* (str root "/config"))
      (fs/spit fs* (str root "/config/isaac.edn")
               (pr-str {:google {:push {:endpoint        "https://isaac.example/google/pubsub"
                                        :service-account "pubsub-push@marigold.iam.gserviceaccount.com"}}}))
      (with-redefs [loader/snapshot (fn [_] nil)]
        (nexus/-with-nexus {:root root :fs fs*}
          (should= {:name :google-pubsub :scopes #{:google/push}}
                   (sut/verify (request (mint {:aud   "https://isaac.example/google/pubsub"
                                               :email "pubsub-push@marigold.iam.gserviceaccount.com"}))))))))
  )
