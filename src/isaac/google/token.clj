(ns isaac.google.token
  "Public seam other modules call for a valid Google access token.
   Refreshes when needed; never prompts.

   One token per organization: each has its own OAuth client and its own
   Google user, so its tokens are stored under its own provider key —
   \"google/<organization>\" — and refreshed with its own credentials. There is
   no unnamed Google login (isaac-okfj)."
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.google.oauth :as oauth]
    [isaac.google.tenants :as tenants]
    [isaac.llm.auth.store :as auth-store]
    [isaac.nexus :as nexus]))

(def PROVIDER "google")

(defn- oauth-creds [config id]
  (:oauth (tenants/tenant-config config id)))

(defn- auth-root []
  (or (nexus/get :root) (root/current-root)))

(defn- feature-fs []
  (or (fs/instance) (fs/real-fs)))

(defn- configured? [snapshot]
  (some (fn [[_ tenant]] (get-in tenant [:oauth :client-id]))
        (tenants/tenants snapshot)))

(defn- load-config []
  (let [snap (loader/snapshot "google token")]
    (if (configured? snap)
      snap
      (or (:config (loader/load-config-result {:root (auth-root) :fs (feature-fs)}))
          snap
          {}))))

(defn- login-message
  "`isaac google login` for one organization, named — every login is one
   organization's."
  [id]
  (if id
    (str "Missing Google login for organization " (name id)
         ". Run `isaac google login --tenant " (name id) "` first.")
    (str "No Google organization named. Configure google.<organization> and run "
         "`isaac google login --tenant <organization>` first.")))

(defn- refresh-via-http! [auth-dir fs* provider tokens creds]
  (let [response (oauth/refresh! creds (:refresh tokens))]
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
        (auth-store/save-tokens! auth-dir provider
                                 (cond-> response
                                   (not (:refresh_token response))
                                   (assoc :refresh_token (:refresh tokens)))
                                 fs*)
        {:tokens (auth-store/load-tokens auth-dir provider fs*)}))))

(defn resolve-tokens
  "Returns `id`'s stored token map (with :access) after refreshing if needed,
   or an error map {:error :auth-failed :message ...}. With no organization
   named, acts as the one bound to this thread, else the only one configured."
  ([]
   (resolve-tokens (load-config)))
  ([config]
   (resolve-tokens config nil))
  ([config id]
   (let [id       (tenants/resolve-id config id)
         provider (tenants/auth-provider id)
         auth-dir (auth-root)
         fs*      (feature-fs)
         tokens   (when provider (auth-store/load-tokens auth-dir provider fs*))]
     (cond
       (nil? tokens)
       {:error :auth-failed :message (login-message id)}

       (not (auth-store/token-needs-refresh? tokens))
       tokens

       (str/blank? (:refresh tokens))
       {:error :auth-failed :message (login-message id)}

       :else
       (let [result (refresh-via-http! auth-dir fs* provider tokens (oauth-creds config id))]
         (or (:tokens result) result))))))

(defn token
  "Valid Google access token string for an organization, refreshing when needed.
   Never prompts. Throws on auth failure so callers cannot silently proceed
   without a token."
  ([] (token nil))
  ([id]
   (let [result (resolve-tokens (load-config) id)]
     (if (:error result)
       (throw (ex-info (or (:message result) "Google authentication failed")
                       result))
       (:access result)))))
