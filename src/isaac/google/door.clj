(ns isaac.google.door
  "The doors Google knocks on: the Pub/Sub push door, and the OAuth callback
   the operator's browser lands on at the end of a login.

   isaac-http verifies Google's OIDC token against a data-shaped rule whose
   :audience and :claims may be config refs (static paths into live config).
   One rule therefore proves one organization, so a host gets one rule per
   configured organization, each pointed at that organization's own endpoint
   and push service account, granting a principal named after it. There is no
   unnamed rule, because there is no unnamed organization (isaac-okfj).

   The callback door is the other kind: a consent redirect arrives from the
   operator's own browser carrying no credentials at all, so it cannot be
   authenticated — it can only be scoped. A code verifier opens it for GET on
   that one path and grants one scope the callback route alone requires; the
   handshake itself is proved by the state nonce and the PKCE verifier the
   pending login holds, not by the request (isaac-2abl)."
  (:require
    [clojure.string :as str]
    [isaac.google.tenants :as tenants]))

(def ISSUER "https://accounts.google.com")
(def JWKS "https://www.googleapis.com/oauth2/v3/certs")
(def SCOPE :google/push)

(def CALLBACK-PATH "/google/oauth/callback")
(def CALLBACK-SCOPE :google/oauth-callback)
(def CALLBACK-PRINCIPAL :google/oauth-callback)

(defn principal-name
  "The principal an organization's rule grants, always named after it."
  [id]
  (when id (keyword "google-pubsub" (name id))))

(defn- trust-rule [config id]
  (let [push (conj (tenants/config-path config id) :push)
        name* (principal-name id)]
    {:issuer    ISSUER
     :jwks      JWKS
     :audience  (conj push :endpoint)
     :claims    {:email          (conj push :service-account)
                 :email_verified true}
     :principal {:name name* :scopes #{SCOPE}}}))

(defn trust-rules
  "{rule-id rule} for every configured organization. Empty for a host with no
   :google config at all."
  [config]
  (into {}
        (map (fn [id] [(principal-name id) (trust-rule config id)]))
        (tenants/ids config)))

(defn registrar
  "isaac-http's identity seam, resolved at call time — isaac-http is something
   this module talks to rather than depends on, so a host without it simply
   has no door to register with."
  []
  (try
    (some-> (requiring-resolve 'isaac.http.auth/register-identity-entry!) deref)
    (catch Exception _ nil)))

(defn register-trust-rules!
  "Register every tenant's rule with the http identity seam. `register!` is
   `isaac.http.auth/register-identity-entry!`; nil when this host has no http
   module, in which case there is no door to register with."
  ([config]
   (register-trust-rules! config (registrar)))
  ([config register!]
   (when register!
     (doseq [entry (trust-rules config)]
       (register! entry))
     (count (trust-rules config)))))

;; ---- the OAuth callback door --------------------------------------------

(defn public-base
  "Where the login sends the operator back: the redirect base an organization
   states outright (oauth.redirect-base), or nil. Deliberately NOT derived from
   the push endpoint: only a Web-application OAuth client can carry a redirect
   URI, and a host on a Desktop client must keep the paste-a-code login even
   though it publishes a push door (Micah, 2026-09-23)."
  [config id]
  (let [tenant (tenants/tenant-config config id)]
    (some-> (get-in tenant [:oauth :redirect-base])
            str str/trim not-empty (str/replace #"/+$" ""))))

(defn redirect-uri
  "The redirect_uri `id`'s consent URL asks Google for, and the one the code
   exchange must repeat. nil when this host has no public base, which is what
   keeps the paste-a-code login."
  [config id]
  (when-let [base (public-base config id)]
    (str base CALLBACK-PATH)))

(def callback-verifier
  "isaac-http code verifier for the consent redirect. It grants one principal,
   on one method and path, with one scope — the scope the callback route
   requires and nothing else in Isaac accepts."
  (with-meta
    (fn [request]
      (when (and (= :get (:request-method request))
                 (= CALLBACK-PATH (:uri request)))
        {:name CALLBACK-PRINCIPAL :scopes #{CALLBACK-SCOPE}}))
    {:name CALLBACK-PRINCIPAL}))

(defn register-callback!
  "Open the callback door on a host that serves at least one Google
   organization. A host with none has no login to finish, and registering a
   verifier would turn isaac-http's auth on for everything it serves."
  ([config] (register-callback! config (registrar)))
  ([config register!]
   (when (and register! (seq (tenants/ids config)))
     (register! callback-verifier)
     1)))
