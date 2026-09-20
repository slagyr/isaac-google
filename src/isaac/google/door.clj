(ns isaac.google.door
  "The push door's trust rules — one per Google organization.

   isaac-http verifies Google's OIDC token against a data-shaped rule whose
   :audience and :claims may be config refs (static paths into live config).
   One rule therefore proves one organization, so a host serving several needs
   one rule per tenant, each pointed at that tenant's own endpoint and push
   service account, granting a principal named after the tenant. A flat host
   keeps the plain `:google-pubsub` rule and the paths it always had
   (isaac-1zkz)."
  (:require
    [isaac.google.tenants :as tenants]))

(def ISSUER "https://accounts.google.com")
(def JWKS "https://www.googleapis.com/oauth2/v3/certs")
(def SCOPE :google/push)

(defn principal-name
  "The principal a tenant's rule grants. The flat host's is the plain
   `:google-pubsub` the door has always answered to."
  [id]
  (if (or (nil? id) (= tenants/DEFAULT id))
    :google-pubsub
    (keyword "google-pubsub" (name id))))

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
