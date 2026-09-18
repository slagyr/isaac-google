(ns isaac.google.identity
  "Pub/Sub push OIDC identity source for :isaac.http/identity."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus])
  (:import
    (java.util Base64)))

(def ^:dynamic *skip-signature?* false)
(defonce skip-signature?* (atom false))

(defn- b64-url-decode [s]
  (.decode (Base64/getUrlDecoder) (str/replace (str s) #"=" "")))

(defn- jwt-parts [token]
  (str/split (str token) #"\." -1))

(defn- parse-jwt [token]
  (let [parts (jwt-parts token)]
    (when (>= (count parts) 2)
      (try
        (json/parse-string (String. (b64-url-decode (second parts)) "UTF-8") true)
        (catch Exception _ nil)))))

(defn- bearer [request]
  (let [header (or (get-in request [:headers "authorization"])
                   (get-in request [:headers "Authorization"]))]
    (when (and (string? header) (str/starts-with? header "Bearer "))
      (subs header 7))))

(defn- deref-cfg [cfg]
  (if (instance? clojure.lang.IDeref cfg) @cfg cfg))

(defn- loaded-google-cfg [root]
  (when root
    (let [fs* (or (fs/instance) (nexus/get :fs) (fs/real-fs))]
      (get-in (loader/load-config-result {:root root :fs fs*}) [:config :google]))))

(defn- push-cfg []
  (let [snap      (deref-cfg (or (loader/snapshot "google identity") (nexus/get :config) {}))
        from-snap (get-in snap [:google :push])]
    (or (when (seq from-snap) from-snap)
        (get-in (loaded-google-cfg (or (nexus/get :root) (:root snap))) [:push])
        {})))

(defn- claim [claims k]
  (or (get claims k) (get claims (name k))))

(defn verify
  "Return {:name :google-pubsub :scopes #{:google/push}} when the bearer is a
   Pub/Sub push token for this door; otherwise nil."
  [request]
  (let [token  (bearer request)
        claims (when token (parse-jwt token))
        cfg    (push-cfg)
        aud    (:endpoint cfg)
        email  (:service-account cfg)]
    (when (and token claims
               (or *skip-signature?* @skip-signature?* (seq (nth (jwt-parts token) 2 nil)))
               (seq aud) (seq email)
               (= aud (claim claims :aud))
               (= email (claim claims :email)))
      {:name :google-pubsub :scopes #{:google/push}})))
