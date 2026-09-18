(ns isaac.google.token
  "Public seam other modules call for a valid Google access token.
   Refreshes when needed; never prompts."
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.google.oauth :as oauth]
    [isaac.llm.auth.store :as auth-store]
    [isaac.nexus :as nexus]))

(def PROVIDER "google")

(defn- oauth-creds [config]
  (get-in config [:google :oauth]))

(defn- auth-root []
  (or (nexus/get :root) (root/current-root)))

(defn- feature-fs []
  (or (fs/instance) (fs/real-fs)))

(defn- load-config []
  (let [snap (loader/snapshot "google token")]
    (if (get-in snap [:google :oauth :client-id])
      snap
      (or (:config (loader/load-config-result {:root (auth-root) :fs (feature-fs)}))
          snap
          {}))))

(defn- refresh-via-http! [auth-dir fs* tokens config]
  (let [response (oauth/refresh! (oauth-creds config) (:refresh tokens))]
    (cond
      (or (= "invalid_grant" (:error response))
          (= "invalid_grant" (get-in response [:body :error])))
      {:error   :auth-failed
       :message (oauth/invalid-grant-message)}

      (or (:error response) (not (:access_token response)))
      {:error   (or (:error response) :auth-failed)
       :message (or (:message response)
                    "Google OAuth token refresh failed.")}

      :else
      (do
        (auth-store/save-tokens! auth-dir PROVIDER
                                 (cond-> response
                                   (not (:refresh_token response))
                                   (assoc :refresh_token (:refresh tokens)))
                                 fs*)
        {:tokens (auth-store/load-tokens auth-dir PROVIDER fs*)}))))

(defn resolve-tokens
  "Returns the stored token map (with :access) after refreshing if needed,
   or an error map {:error :auth-failed :message ...}."
  ([]
   (resolve-tokens (load-config)))
  ([config]
   (let [auth-dir (auth-root)
         fs*      (feature-fs)
         tokens   (auth-store/load-tokens auth-dir PROVIDER fs*)]
     (cond
       (nil? tokens)
       {:error   :auth-failed
        :message "Missing Google login. Run `isaac google login` first."}

       (not (auth-store/token-needs-refresh? tokens))
       tokens

       (str/blank? (:refresh tokens))
       {:error   :auth-failed
        :message "Missing Google login. Run `isaac google login` first."}

       :else
       (let [result (refresh-via-http! auth-dir fs* tokens config)]
         (or (:tokens result) result))))))

(defn token
  "Valid Google access token string, refreshing when needed. Never prompts.
   Throws on auth failure so callers cannot silently proceed without a token."
  []
  (let [result (resolve-tokens)]
    (if (:error result)
      (throw (ex-info (or (:message result) "Google authentication failed")
                      result))
      (:access result))))
