(ns isaac.google.service-account
  "Isaac's own identity for Pub/Sub: a service account, never the human.

   Publishing to a topic is machine work, and asking a person to consent to
   it costs `https://www.googleapis.com/auth/pubsub` — a Google *Cloud
   Platform* scope. A Workspace that configures \"Google Cloud console and
   SDK session control\" applies its reauthentication frequency to every app
   requiring a Cloud Platform scope, non-Google apps included; the default is
   16 hours. One machine scope on the login therefore drags the whole Google
   grant — Gmail, Chat, directory — under that clock. On yopp it killed the
   refresh token every ~15 hours (measured 14h50m), survived a fresh login,
   and left the stored token byte-identical because Google revoked it
   server-side. Nothing in this module could have prevented it (isaac-ey6q).

   So Pub/Sub authenticates as a service account of its own and the user's
   login asks for no Cloud scope at all (isaac-286x). The Workspace Events
   subscriptions stay on the human's token: `spaces/-` means \"every space
   *this account* belongs to\", which is a statement about a person, and
   `subscriptions.create` needs no Pub/Sub scope to name a topic.

   The credential is a service-account JSON key named by path, absent by
   default. Its absence is a config error the loader reports at startup — see
   `check-credentials` — not a surprise on the first heartbeat hours later."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.google.health :as health]
    [isaac.google.tenants :as tenants]
    [isaac.llm.http :as llm-http]
    [isaac.nexus :as nexus])
  (:import
    (java.security KeyFactory Signature)
    (java.security.spec PKCS8EncodedKeySpec)
    (java.time Instant)
    (java.util Base64)))

(def SCOPE
  "The one scope the service account asks for. It is a Cloud Platform scope,
   which is precisely why it may not ride on a person's grant."
  "https://www.googleapis.com/auth/pubsub")

(def TOKEN-URL "https://oauth2.googleapis.com/token")

(def JWT-BEARER "urn:ietf:params:oauth:grant-type:jwt-bearer")

(def ASSERTION-TTL-SECONDS
  "How long the signed assertion is good for. Google caps it at an hour."
  3600)

(def REFRESH-MARGIN-SECONDS
  "Spend a cached token only while it has this much life left, so a publish
   never starts with a token that expires mid-flight."
  60)

;; region ----- the credential -----

(defn credentials-key
  "The config key an operator sets for `id`'s Pub/Sub service account."
  [config id]
  (str/join "." (concat (map name (tenants/config-path config (or id :<organization>)))
                        ["pubsub" "credentials-file"])))

(defn credentials-file
  "The path `id` names for its service-account key, or nil."
  [config id]
  (not-empty (str/trim (str (get-in (tenants/tenant-config config id)
                                    [:pubsub :credentials-file])))))

(defn- runtime-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- isaac-root [config]
  (or (:root config) (nexus/get :root) (root/current-root)))

(defn- resolve-path
  "A key file is named absolutely, or relative to the Isaac root — the same
   place every other file this host keeps lives."
  [config path]
  (if (str/starts-with? path "/")
    path
    (str (or (isaac-root config) ".") "/" path)))

(def MISSING-REASON
  "must name a service-account JSON key: Pub/Sub publishes as a service account of Isaac's own, never as the signed-in user (isaac-286x)")

(defn read-credentials
  "`id`'s service-account key, parsed: `{:credentials …}`, or `{:error …}`
   saying tersely what is wrong with it. Never throws — the config check is
   what refuses at startup, and a publish reports rather than unwinds."
  [config id]
  (if-let [path (credentials-file config id)]
    (let [fs*      (runtime-fs)
          resolved (resolve-path config path)]
      (if-not (fs/exists? fs* resolved)
        {:error (str "names no file: " resolved)}
        (let [parsed (try
                       {:value (json/parse-string (fs/slurp fs* resolved) true)}
                       (catch Exception e
                         {:bad (or (.getMessage e) (str e))}))
              value  (:value parsed)]
          (cond
            (:bad parsed)
            {:error (str "is not readable JSON: " (:bad parsed))}

            (or (not (map? value))
                (str/blank? (str (:client_email value)))
                (str/blank? (str (:private_key value))))
            {:error (str "must be a service-account JSON key with client_email and private_key: " resolved)}

            :else {:credentials value}))))
    {:error MISSING-REASON}))

(defn explain
  "A terse reason, said where a human will read it: keyed by the config key
   they would set."
  [config id reason]
  (str (credentials-key config id) " " reason))

;; endregion

;; region ----- the signed assertion -----

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- utf8 [^String s]
  (.getBytes s "UTF-8"))

(defn- private-key [pem]
  (let [body (-> (str pem)
                 (str/replace #"-----[A-Z ]+-----" "")
                 (str/replace #"\s" ""))]
    (.generatePrivate (KeyFactory/getInstance "RSA")
                      (PKCS8EncodedKeySpec. (.decode (Base64/getDecoder) body)))))

(defn assertion
  "The RS256 JWT Google trades for an access token: signed by the key, from
   the service account, for the Pub/Sub scope and nothing else."
  [credentials ^Instant now]
  (let [iat    (.getEpochSecond now)
        header (cond-> {:alg "RS256" :typ "JWT"}
                 (not (str/blank? (str (:private_key_id credentials))))
                 (assoc :kid (:private_key_id credentials)))
        claims {:iss   (:client_email credentials)
                :scope SCOPE
                :aud   (or (not-empty (str (:token_uri credentials))) TOKEN-URL)
                :iat   iat
                :exp   (+ iat ASSERTION-TTL-SECONDS)}
        input  (str (b64url (utf8 (json/generate-string header))) "."
                    (b64url (utf8 (json/generate-string claims))))
        signer (doto (Signature/getInstance "SHA256withRSA")
                 (.initSign (private-key (:private_key credentials)))
                 (.update (utf8 input)))]
    (str input "." (b64url (.sign signer)))))

(defn exchange!
  "HTTP seam. Feature steps redef this. Trades the signed assertion for an
   access token."
  [token-uri assertion]
  (llm-http/post-json! token-uri
                       {"Content-Type" "application/json"}
                       {:grant_type JWT-BEARER
                        :assertion  assertion}))

;; endregion

;; region ----- the token -----

(defonce ^:private tokens* (atom {}))

(defn reset-tokens!
  "Forget every cached service-account token. Specs and feature scenarios
   call this; nothing in production needs to."
  []
  (reset! tokens* {}))

(defn- fresh? [entry ^Instant now]
  (boolean (and entry
                (.isAfter ^Instant (:expires-at entry)
                          (.plusSeconds now REFRESH-MARGIN-SECONDS)))))

(defn- refusal-message [credentials response]
  (str "Google refused the Pub/Sub service account "
       (:client_email credentials) ": "
       (or (not-empty (str (:message response)))
           (not-empty (str (get-in response [:body :error_description])))
           (not-empty (str (get-in response [:body :error])))
           (not-empty (str (:error_description response)))
           (not-empty (str (:error response)))
           "no access token")))

(defn resolve-token
  "An access token for `id`'s Pub/Sub service account: `{:access …}` or
   `{:error …}`. Cached per organization until it is nearly spent — a
   heartbeat an hour is not worth a token exchange an hour."
  ([config id] (resolve-token config id (Instant/now)))
  ([config id ^Instant now]
   (let [{:keys [credentials error]} (read-credentials config id)]
     (if error
       {:error (explain config id error)}
       (let [cache-key [id (:client_email credentials)]
             cached    (get @tokens* cache-key)]
         (if (fresh? cached now)
           {:access (:access cached)}
           (let [token-uri (or (not-empty (str (:token_uri credentials))) TOKEN-URL)
                 response  (try
                             (exchange! token-uri (assertion credentials now))
                             (catch Exception e
                               {:error :exchange-failed :message (or (.getMessage e) (str e))}))]
             (if (or (:error response) (str/blank? (str (:access_token response))))
               {:error (refusal-message credentials response)}
               (let [expires (.plusSeconds now (long (or (:expires_in response) 3600)))]
                 (swap! tokens* assoc cache-key {:access     (:access_token response)
                                                 :expires-at expires})
                 {:access (:access_token response)})))))))))

(defn auth-headers
  "What a Pub/Sub call carries: `{:headers …}` bearing the service account's
   token, or `{:error …}`. Never the signed-in user's token."
  [config id]
  (let [{:keys [access error]} (resolve-token config id)]
    (if error
      {:error error}
      {:headers {"Authorization" (str "Bearer " access)
                 "Content-Type"  "application/json"}})))

;; endregion

;; region ----- the config check -----

(defn check-credentials
  "An organization whose heartbeat is on must name the service account Isaac
   publishes it as. Without one the host starts, validates OK, and only says
   so an hour later when the first heartbeat cannot be published — which is
   the shape of failure this whole bean exists to end (isaac-286x).

   The demand follows the heartbeat, not the topic. A topic is also the
   address Google *pushes to*, and receiving a push costs no credential at
   all: Gmail and Chat keep working with no service account in sight. Only
   the synthetic heartbeat publishes on a timer. Tying the demand to `:topic`
   would refuse to start a host that merely receives — and, where an
   organization forbids service-account keys, would leave no way to run at
   all (yopp, 2026-09-24: `constraints/iam.disableServiceAccountKeyCreation`).
   Turning the heartbeat off is then a real choice: the push pipeline loses
   its silence detector, and nothing else changes.

   `smoke --send-live` publishes too, but it is a command someone runs and
   watches; it reports the missing credential itself."
  [{:keys [config]}]
  {:errors
   (vec
     (keep (fn [[id tenant]]
             (when (and (not (str/blank? (str (:topic tenant))))
                        (health/heartbeat-enabled? config id))
               (when-let [reason (:error (read-credentials config id))]
                 {:key   (credentials-key config id)
                  :value reason})))
           (sort-by key (tenants/tenants config))))})

;; endregion
