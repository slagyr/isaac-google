(ns isaac.google.http
  "The module's two public doors.

   POST /google/pubsub — persist then 204. Processing is the inbox worker.

   One door serves every Google organization. Which one sent a push is told
   twice: by the project in the subscription name, and by the service account
   whose OIDC token the request proved. Both must agree, and the tenant they
   agree on is stamped on the persisted event so the worker acts as the right
   organization (isaac-1zkz).

   A host that configured no organization has no one to own a push, so the
   door refuses it rather than keeping an event nobody answers for — which is
   what the flat `:google {:project ...}` shape now is (isaac-okfj).

   GET /google/oauth/callback — the end of `isaac google login`. The operator
   approves at Google, Google sends the browser here, and this host finishes
   the login: no code is ever copied out of an address bar. The request
   carries no credentials, so what it has to prove it proves with the pending
   login the CLI left behind — the state nonce names it, the PKCE verifier
   inside it is what Google checks, and ten minutes is all it lives
   (isaac-2abl)."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.google.door :as door]
    [isaac.google.health :as health]
    [isaac.google.heartbeat :as heartbeat]
    [isaac.google.inbox :as inbox]
    [isaac.google.logins :as logins]
    [isaac.google.oauth :as oauth]
    [isaac.google.push :as push]
    [isaac.google.tenants :as tenants]
    [isaac.llm.auth.store :as auth-store]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory])
  (:import
    (java.net URLDecoder)
    (java.time Instant)))

(defn- read-body [request]
  (let [body (:body request)]
    (cond
      (nil? body) {}
      (string? body) (json/parse-string body true)
      (map? body) body
      :else (json/parse-string (slurp body) true))))

(defn- runtime-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- live-config [request root]
  (or (:isaac/config request)
      (let [snap (loader/snapshot "google push door")]
        (if (seq (tenants/tenants snap))
          snap
          (or (when root
                (:config (loader/load-config-result {:root root :fs (runtime-fs)})))
              snap
              {})))))

(defn- record-health! [root event now]
  (health/record-door-hit! root now)
  (when-let [k (or (get-in event [:data :space])
                   (get-in event [:data :spaceName])
                   (some-> (get-in event [:data :message :name])
                           (as-> n (second (re-find #"^(spaces/[^/]+)" n)))))]
    (health/record-last-event! root k now)))

(defn- accept-heartbeat!
  "The scheduled heartbeat: record that it arrived and stop. It is not
   persisted, so no worker drains it, no handler sees it and no turn starts;
   and it is not a last-event, so it never quiets the silence watch it exists
   to complement (isaac-an14).

   Which organization it belongs to is settled the same way every other push
   is — the project in the subscription name and the service account that
   signed the token — never by anything in the body. A publisher outside
   Isaac cannot name a tenant it has not proved (isaac-clly)."
  [root tenant event now]
  (when root
    (health/record-door-hit! root now)
    (health/record-heartbeat! root tenant now))
  (log/debug :google/heartbeat-received :tenant tenant :message-id (:message-id event))
  {:status 204 :headers {} :body ""})

(defn- refuse-no-organization [event principal]
  (log/warn :google/no-organization
            :principal principal
            :message-id (:message-id event)
            :message "no Google organization is configured; :google is a map of organization id to config")
  {:status 401 :headers {} :body ""})

(defn- refuse-mismatch [decision event principal]
  (log/warn :google/tenant-mismatch
            :principal principal
            :message-id (:message-id event)
            :subscription-tenant (:tenant decision)
            :principal-tenant (:principal-tenant decision))
  {:status 403 :headers {} :body ""})

(defn handler [request]
  (let [root      (or (nexus/get :root) (get-in request [:isaac/root]))
        event     (push/unwrap (read-body request))
        principal (get-in request [:isaac/principal :name])
        config    (live-config request root)
        decision  (tenants/tenant-of-push config event principal)]
    (cond
      (empty? (tenants/tenants config))
      (refuse-no-organization event principal)

      (:refused decision)
      (refuse-mismatch decision event principal)

      :else
      (let [tenant (:tenant decision)
            event  (assoc event :tenant tenant)
            now    (or (memory/now) (Instant/now))]
        (if (heartbeat/heartbeat? event)
          (accept-heartbeat! root tenant event now)
          (do
            (when root
              (record-health! root event now))
            (inbox/accept! root event)
            (log/info :google/push-received
                      :principal (or principal :google-pubsub)
                      :message-id (:message-id event)
                      :tenant tenant
                      :type (:type event))
            {:status 204 :headers {} :body ""}))))))

;; ---- GET /google/oauth/callback ------------------------------------------

(defn- query-params
  "The callback's query, whichever way the request carries it: ring's
   :query-string, or a :uri the caller never split."
  [request]
  (let [query (or (not-empty (str (:query-string request)))
                  (second (str/split (str (:uri request)) #"\?" 2)))]
    (into {}
          (keep (fn [pair]
                  (let [[k v] (str/split pair #"=" 2)]
                    (when (seq k)
                      [(keyword (URLDecoder/decode k "UTF-8"))
                       (URLDecoder/decode (or v "") "UTF-8")]))))
          (str/split (or query "") #"&"))))

(defn- escape
  "Whatever Google echoed back lands in a page this host serves."
  [text]
  (-> (str text)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- page [status message]
  {:status  status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (str "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                 "<title>Isaac</title></head><body><p>" (escape message)
                 "</p></body></html>")})

(defn- store-tokens! [root id tokens]
  (auth-store/save-tokens! root (tenants/auth-provider id) tokens (runtime-fs)))

(defn- complete-login! [root config id state code]
  (let [creds  (:oauth (tenants/tenant-config config id))
        verifier (:code-verifier (logins/pending root state))
        tokens (oauth/exchange-code! (assoc creds
                                            :redirect-uri (door/redirect-uri config id)
                                            :code-verifier verifier)
                                     code)]
    (cond
      (:error tokens)
      (page 502 (str "Google refused the sign-in: " (or (:error tokens) "unknown error")
                     ". Run `isaac google login` again."))

      (not (:access_token tokens))
      (page 502 "Google answered the sign-in without an access token. Run `isaac google login` again.")

      :else
      (do
        (store-tokens! root id tokens)
        (logins/forget! root state)
        (log/info :google/login-completed :tenant id)
        (page 200 (str "Signed in as " (or (:account creds) "the Google user")
                       " for organization " (name id) ". You can close this tab."))))))

(defn oauth-callback
  "The consent redirect. Answers the operator's browser with a plain page and
   stores nothing it cannot account for."
  [request]
  (let [root    (or (nexus/get :root) (:isaac/root request))
        config  (live-config request root)
        {:keys [state code error]} (query-params request)
        record  (logins/pending root state)
        now     (or (memory/now) (Instant/now))]
    (cond
      (nil? record)
      (page 400 "That sign-in is unknown to this Isaac. Run `isaac google login` again.")

      (logins/expired? record now)
      (do (logins/forget! root state)
          (page 410 "That sign-in expired. Run `isaac google login` again."))

      (seq error)
      (page 400 (str "Google refused the sign-in: " error "."))

      (str/blank? code)
      (page 400 "Google sent no authorization code. Run `isaac google login` again.")

      (nil? (tenants/tenant-config config (:tenant record)))
      (page 400 (str "This Isaac no longer serves organization " (name (:tenant record))
                     ". Nothing was stored."))

      :else
      (complete-login! root config (:tenant record) state code))))
