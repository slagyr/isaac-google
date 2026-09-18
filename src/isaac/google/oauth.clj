(ns isaac.google.oauth
  (:require
    [clojure.string :as str]
    [isaac.llm.http :as llm-http])
  (:import (java.net URLEncoder)))

(def AUTH-URL "https://accounts.google.com/o/oauth2/v2/auth")
(def TOKEN-URL "https://oauth2.googleapis.com/token")
(def SEVEN-DAY-SECONDS 604800)

(defn- url-encode [s]
  (URLEncoder/encode (str s) "UTF-8"))

(defn authorization-url
  [{:keys [client-id scopes redirect-uri state]}]
  (let [scope-str (str/join " " scopes)
        params    [["client_id" client-id]
                   ["redirect_uri" redirect-uri]
                   ["response_type" "code"]
                   ["scope" scope-str]
                   ["access_type" "offline"]
                   ["prompt" "consent"]
                   ["state" state]]]
    (str AUTH-URL "?"
         (str/join "&" (map (fn [[k v]] (str k "=" (url-encode v))) params)))))

(defn exchange-code!
  [{:keys [client-id client-secret redirect-uri]} code]
  (llm-http/post-json! TOKEN-URL
                       {"Content-Type" "application/json"}
                       {:grant_type    "authorization_code"
                        :code          code
                        :client_id     client-id
                        :client_secret client-secret
                        :redirect_uri  redirect-uri}))

(defn refresh!
  [{:keys [client-id client-secret]} refresh-token]
  (llm-http/post-json! TOKEN-URL
                       {"Content-Type" "application/json"}
                       {:grant_type    "refresh_token"
                        :refresh_token refresh-token
                        :client_id     client-id
                        :client_secret client-secret}))

(defn invalid-grant-message []
  (str "Google rejected the refresh token (invalid_grant). "
       "The consent screen is still in Testing — publish the app or "
       "re-run `isaac google login`."))

(defn seven-day-token? [expires-in]
  (and (number? expires-in)
       (>= expires-in SEVEN-DAY-SECONDS)))
