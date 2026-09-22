(ns isaac.google-steps
  "Module-owned Google login feature steps. One helper each — everything
   else is existing foundation/agent steps."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.fs :as fs]
    [cheshire.core :as json]
    [isaac.config.loader :as loader]
    [isaac.google.component :as google-component]
    [isaac.google.events :as google-events]
    [isaac.google.handler :as google-handler]
    [isaac.google.health :as google-health]
    [isaac.google.heartbeat :as google-heartbeat]
    [isaac.google.inbox :as google-inbox]
    [isaac.google.people :as google-people]
    [isaac.google.registration :as google-registration]
    [isaac.google.tenants :as tenants]
    [isaac.google.token :as google-token]
    [isaac.google.worker :as google-worker]
    [isaac.http.oidc :as oidc]
    [isaac.http.oidc-fixture :as oidc-fixture]
    [isaac.logger :as log]
    [isaac.tool.memory :as memory]
    [isaac.llm.auth.store :as auth-store]
    [isaac.llm.http :as llm-http]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus])
  (:import
    (java.util Base64)))

(helper! isaac.google-steps)

(def ^:private default-events-state
  {:subs {} :grant-expires nil :rejects {}})

(def ^:private events-state* (atom default-events-state))

(def ^:private default-people-state {:known {} :error nil})

(def ^:private people-state* (atom default-people-state))

(def ^:private original-events-request* (atom nil))

(declare restore-people-stub!)

(g/after-scenario
  (fn []
    (alter-var-root #'discovery/*foundation-index-override* (constantly nil))
    (reset! events-state* default-events-state)
    (reset! people-state* default-people-state)
    (restore-people-stub!)
    (google-people/reset-memo!)
    (google-registration/reset-registrations!)))

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- feature-root []
  (or (g/get :root) "target/test-state"))

(defn- feature-config
  "The config this scenario wrote: the running server's when there is one,
   else loaded from the feature root the way the CLI loads it."
  []
  (let [served (or (g/get :server-config) {})]
    (if (seq (tenants/tenants served))
      served
      (or (:config (loader/load-config-result {:root (feature-root) :fs (feature-fs)})) {}))))

(defn- feature-provider
  "The auth-store provider key a scenario means by \"the google auth store\":
   the host's only Google organization. Every login belongs to one
   organization, so a scenario that configured none has no store to speak of
   (isaac-okfj)."
  []
  (or (tenants/auth-provider (tenants/resolve-id (feature-config)))
      (throw (ex-info (str "this scenario configured no Google organization: "
                           "write google.<organization>.oauth.client-id")
                      {}))))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (feature-fs)}
    (f)))

(defn- parse-expires [n]
  (if (string? n) (parse-long n) n))

(defn- stub-response []
  (or (g/get :google-token-stub)
      {:error :unknown :message "no Google token stub configured"}))

(defn- record-google-http! [url headers body]
  ;; :key "value" satisfies isaac.llm.providers-steps' match-object, which
  ;; conses the table header ["key" "value"] onto :rows (set-of-maps vs set-of-strings).
  (let [req {:url url :headers headers :body body :stream false :key "value"}]
    (when-let [calls (g/get :google-http-calls)]
      (swap! calls conj req))
    (g/update! :outbound-http-requests (fn [prior] (vec (conj (or prior []) req))))
    (g/assoc! :outbound-http-request req)
    req))

(defn- stub-post-json! [url headers body & _opts]
  (record-google-http! url headers body)
  (stub-response))

(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (when-not (g/get :google-http-calls)
      (g/assoc! :google-http-calls (atom [])))
    (with-redefs [llm-http/post-json! stub-post-json!]
      (thunk))))

(fcli/register-isaac-run-postflight!
  (fn []
    (when-let [calls (seq @(or (g/get :google-http-calls) (atom [])))]
      (g/update! :outbound-http-requests
                 (fn [prior]
                   (vec (distinct (concat (or prior []) calls)))))
      (g/assoc! :outbound-http-request (last calls)))))

(defn google-token-endpoint-returns-access-and-refresh [at rt n]
  (g/assoc! :google-token-stub {:access_token  at
                                :refresh_token rt
                                :expires_in    (parse-expires n)})
  (g/assoc! :google-http-calls (atom [])))

(defn google-token-endpoint-returns-access [at n]
  (g/assoc! :google-token-stub {:access_token at
                                :expires_in   (parse-expires n)})
  (g/assoc! :google-http-calls (atom [])))

(defn google-token-endpoint-rejects-refresh [error]
  (g/assoc! :google-token-stub {:error error :body {:error error}})
  (g/assoc! :google-http-calls (atom [])))

(defn google-auth-store-has-access-and-refresh
  "Then: assert stored tokens. Given (health.feature): seed when empty and no stub is armed."
  [at rt]
  (with-feature-fs
    (fn []
      (let [provider (feature-provider)
            tokens   (auth-store/load-tokens (feature-root) provider (feature-fs))]
        (if (or tokens (g/get :google-token-stub))
          (do
            (g/should tokens)
            (g/should= at (:access tokens))
            (g/should= rt (:refresh tokens)))
          (auth-store/save-tokens! (feature-root) provider
                                   {:access_token  at
                                    :refresh_token rt
                                    :expires_in    3600}
                                   (feature-fs)))))))

(defn google-auth-store-for-tenant-has-access-and-refresh
  "Then: assert one named organization's stored tokens. Given: seed them when
   the store is empty (isaac-1zkz)."
  [tenant at rt]
  (with-feature-fs
    (fn []
      (let [provider (tenants/auth-provider (keyword tenant))
            tokens   (auth-store/load-tokens (feature-root) provider (feature-fs))]
        (if (or tokens (g/get :google-token-stub))
          (do
            (g/should tokens)
            (g/should= at (:access tokens))
            (g/should= rt (:refresh tokens)))
          (auth-store/save-tokens! (feature-root) provider
                                   {:access_token  at
                                    :refresh_token rt
                                    :expires_in    3600}
                                   (feature-fs)))))))

(defn google-auth-store-has-expired-access [rt]
  (with-feature-fs
    (fn []
      (auth-store/save-tokens! (feature-root) (feature-provider)
                               {:access_token  "at-expired"
                                :refresh_token rt
                                :expires_in    -1}
                               (feature-fs)))))

(defn google-access-token-is-resolved []
  (when-not (g/get :google-http-calls)
    (g/assoc! :google-http-calls (atom [])))
  (with-feature-fs
    (fn []
      (with-redefs [llm-http/post-json! stub-post-json!]
        (try
          (let [result (google-token/resolve-tokens)]
            (g/assoc! :google-resolve-result result)
            (g/assoc! :llm-result result)
            (when (:error result)
              (g/assoc! :google-error result)))
          (catch Exception e
            (let [data (or (ex-data e) {:error :auth-failed :message (.getMessage e)})]
              (g/assoc! :google-resolve-result data)
              (g/assoc! :llm-result data)
              (g/assoc! :google-error data)))))))
  (when-let [calls (seq @(or (g/get :google-http-calls) (atom [])))]
    (g/update! :outbound-http-requests
               (fn [prior] (vec (concat (or prior []) calls))))
    (g/assoc! :outbound-http-request (last calls))))

(defn skybeam-contributes-google-scope [scope]
  (alter-var-root #'discovery/*foundation-index-override*
                  (fn [prev]
                    (merge (or prev (discovery/builtin-index))
                           {:marigold.skybeam {:manifest {:id :marigold.skybeam
                                                          :isaac.google/scopes [scope]}}}))))

(defn error-mentions [text]
  (let [err     (or (g/get :google-error) (g/get :llm-result) {})
        message (or (:message err) (g/get :stderr) (g/get :output) "")]
    (g/should (str/includes? (str message) text))))

(defgiven #"the Google token endpoint returns access token \"([^\"]+)\" and refresh token \"([^\"]+)\" expiring in (\d+)"
  isaac.google-steps/google-token-endpoint-returns-access-and-refresh)

(defgiven #"the Google token endpoint returns access token \"([^\"]+)\" expiring in (\d+)"
  isaac.google-steps/google-token-endpoint-returns-access)

(defgiven "the Google token endpoint rejects refresh with {error:string}"
  isaac.google-steps/google-token-endpoint-rejects-refresh)

(defgiven "the google auth store has an expired access token with refresh {rt:string}"
  isaac.google-steps/google-auth-store-has-expired-access)

(defgiven "the skybeam fixture module contributes the Google scope {scope:string}"
  isaac.google-steps/skybeam-contributes-google-scope)

(defwhen "the google access token is resolved"
  isaac.google-steps/google-access-token-is-resolved)

(defthen "the google auth store has access {at:string} and refresh {rt:string}"
  isaac.google-steps/google-auth-store-has-access-and-refresh)

(defthen "the error mentions {text:string}"
  isaac.google-steps/error-mentions)

(defthen #"the google auth store for tenant \"([^\"]+)\" has access \"([^\"]+)\" and refresh \"([^\"]+)\""
  isaac.google-steps/google-auth-store-for-tenant-has-access-and-refresh)

(def ^:private received* (atom {}))
(def ^:private throwers* (atom #{}))
(def ^:private next-claims* (atom nil))

;; ----- Google's push tokens -----
;; Google signs push tokens with a key published at its JWKS URL. The harness
;; plays Google: one RSA key pair, a JWKS stub on isaac-http's fetch seam, and
;; tokens minted with isaac-http's fixture (isaac.http.oidc-fixture).

(def ^:private GOOGLE-KID "google-1")
(def ^:private google-keys* (atom nil))
(def ^:private jwks-hits* (atom 0))

(defn- google-keys []
  (or @google-keys* (reset! google-keys* (oidc-fixture/generate-rsa))))

(defn- google-jwks []
  {:keys [(oidc-fixture/rsa-jwk (:public (google-keys)) GOOGLE-KID)]})

(defn- serve-jwks! [doc-fn]
  (reset! jwks-hits* 0)
  (oidc/reset-jwks-cache!)
  (alter-var-root #'oidc/*fetch-jwks*
                  (constantly (fn [_url] (doc-fn (swap! jwks-hits* inc))))))

(defn- default-claims []
  (let [now (.getEpochSecond (java.time.Instant/now))]
    {:iss            "https://accounts.google.com"
     :aud            "https://isaac.example/google/pubsub"
     :email          "pubsub-push@marigold.iam.gserviceaccount.com"
     :email_verified true
     :iat            now
     :exp            (+ now 3600)}))

(defn- mint-token
  "A push token as Google would mint it, unless the scenario asked for a bad one."
  [claims]
  (let [{:keys [unsigned? foreign-key?]} claims
        claims (dissoc claims :unsigned? :foreign-key?)]
    (cond
      unsigned?    (str (oidc-fixture/b64url (.getBytes "{\"alg\":\"none\",\"kid\":\"google-1\"}" "UTF-8"))
                        "." (oidc-fixture/b64url (.getBytes (json/generate-string claims) "UTF-8")) ".")
      foreign-key? (oidc-fixture/sign-rs256 (:private (oidc-fixture/generate-rsa)) {:kid GOOGLE-KID} claims)
      :else        (oidc-fixture/sign-rs256 (:private (google-keys)) {:kid GOOGLE-KID} claims))))

(defn- handler-for [name]
  (fn [event]
    (when (contains? @throwers* name)
      (throw (ex-info (str name " boom") {:handler name})))
    (swap! received* update name (fnil conj []) event)))

(defn google-signs-with-test-key []
  (google-keys)
  (serve-jwks! (fn [_hit] {:status 200 :body (google-jwks) :headers {}}))
  (reset! next-claims* nil)
  (reset! received* {})
  (reset! throwers* #{}))

(defn google-jwks-unreachable []
  (serve-jwks! (fn [_hit] {:status 503 :body nil :headers {}})))

(defn google-jwks-misses-then-serves []
  (serve-jwks! (fn [hit] {:status 200 :body (if (= 1 hit) {:keys []} (google-jwks)) :headers {}})))

(defn google-jwks-fetched-times [n]
  (g/should= (long n) (long @jwks-hits*)))

(defn skybeam-handles [event-type]
  (google-handler/register-handler! [event-type (handler-for "skybeam")]))

(defn longwave-handles [event-type]
  (google-handler/register-handler! [event-type (handler-for "longwave")]))

(defn skybeam-throws []
  (swap! throwers* conj "skybeam"))

(defn next-token-audience [aud]
  (swap! next-claims* assoc :aud aud))

(defn next-token-from [email]
  (swap! next-claims* assoc :email email))

(defn next-token-unsigned []
  (swap! next-claims* assoc :unsigned? true))

(defn next-token-foreign-key []
  (swap! next-claims* assoc :foreign-key? true))

(defn next-token-expired []
  (swap! next-claims* assoc :exp (- (.getEpochSecond (java.time.Instant/now)) 600)))

(defn next-token-issuer [iss]
  (swap! next-claims* assoc :iss iss))

(defn- push-envelope [id ce-type data]
  {:message {:messageId  id
             :data       (.encodeToString (Base64/getEncoder) (.getBytes (or data "{}") "UTF-8"))
             :attributes (cond-> {}
                           ce-type (assoc "ce-type" ce-type))}})

(def ^:private GOOGLE-PUSH-IP "35.191.0.10")

(defn google-pushes [id ce-type data]
  (let [claims (merge (default-claims) @next-claims*)
        token  (mint-token claims)
        _      (reset! next-claims* nil)
        body   (json/generate-string (push-envelope id ce-type data))]
    ;; Pushes arrive through the public front (Funnel), so the origin is forwarded.
    ((requiring-resolve 'isaac.http.server-steps/post-request-with-header-and-body)
     "/google/pubsub"
     (str "Authorization: Bearer " token "; X-Forwarded-For: " GOOGLE-PUSH-IP)
     body)))

(defn- server-config
  "The config the running feature server serves — what isaac-http resolves a
   trust rule's config refs against."
  []
  (or (when-let [cfg-fn (:cfg-fn (g/get :server-handler-opts))] (cfg-fn))
      (g/get :server-config)
      {}))

(defn google-runtime-component-starts
  "What isaac.runner does at boot and a feature server does not: start this
   module's runtime component, whose first act is registering one push-door
   trust rule per configured organization. Components belong to the runner
   alone (isaac.component.runtime: \"Only isaac.runner invokes start-all!\"),
   so a scenario that needs a tenanted door says so (isaac-1zkz)."
  []
  (nexus/-with-nested-nexus {:fs (feature-fs) :root (feature-root)}
    (google-component/register-door! (server-config) nil)))

(defn google-pushes-signed-on
  "A push as one organization's Pub/Sub sends it: signed by that project's
   push service account and delivered on that project's subscription. Naming
   both is what lets a scenario cross them (isaac-1zkz)."
  [id ce-type email subscription data]
  (let [claims (merge (default-claims) {:email email} @next-claims*)
        token  (mint-token claims)
        _      (reset! next-claims* nil)
        body   (json/generate-string
                 (assoc (push-envelope id ce-type data) :subscription subscription))]
    ((requiring-resolve 'isaac.http.server-steps/post-request-with-header-and-body)
     "/google/pubsub"
     (str "Authorization: Bearer " token "; X-Forwarded-For: " GOOGLE-PUSH-IP)
     body)))

(defn google-pushes-gmail [id data]
  (google-pushes id nil data))

(defn google-pushes-forged [n]
  (dotimes [i (if (string? n) (parse-long n) n)]
    (next-token-foreign-key)
    (google-pushes (str "forged-" i) "google.workspace.chat.message.v1.created" "{}")))

(defn fixture-route-unquoted
  "Planner phrasing (isaac-1jep): GET /fixture requires scope :fixture/read —
   isaac-http's quoted step does not match."
  [method path scope]
  ((requiring-resolve 'isaac.http.server-steps/fixture-route) method path scope))

(defn google-pushes-to [path]
  (let [claims (merge (default-claims) @next-claims*)
        token  (mint-token claims)]
    (reset! next-claims* nil)
    ((requiring-resolve 'isaac.http.server-steps/get-request-with-header)
     path
     (str "Authorization: Bearer " token))))

(defn inbox-worker-ticks []
  (google-worker/tick! (feature-root)))

(defn skybeam-received [id]
  (g/should (some #(= id (:message-id %)) (get @received* "skybeam"))))

(defn skybeam-received-once [id]
  (g/should= 1 (count (filter #(= id (:message-id %)) (get @received* "skybeam")))))

(defn skybeam-received-none []
  (g/should (empty? (get @received* "skybeam"))))

(defn longwave-received [id]
  (g/should (some #(= id (:message-id %)) (get @received* "longwave"))))

(defn inbox-holds [n id]
  (let [n (if (string? n) (parse-long n) n)
        records (filter #(= id (:message-id %)) (google-inbox/pending (feature-root)))]
    (g/should= n (count records))))

(defgiven "Google signs push tokens with a test key"
  isaac.google-steps/google-signs-with-test-key)

(defgiven #"the skybeam fixture module handles Google events of type \"([^\"]+)\""
  isaac.google-steps/skybeam-handles)

(defgiven #"the longwave fixture module handles Google events of type \"([^\"]+)\""
  isaac.google-steps/longwave-handles)

(defgiven "the skybeam handler throws"
  isaac.google-steps/skybeam-throws)

(defgiven #"the next push token has audience \"([^\"]+)\""
  isaac.google-steps/next-token-audience)

(defgiven #"the next push token is from \"([^\"]+)\""
  isaac.google-steps/next-token-from)

(defgiven "the next push token is unsigned"
  isaac.google-steps/next-token-unsigned)

(defgiven "the next push token is signed by a key Google never published"
  isaac.google-steps/next-token-foreign-key)

(defgiven "the next push token is expired"
  isaac.google-steps/next-token-expired)

(defgiven #"the next push token is issued by \"([^\"]+)\""
  isaac.google-steps/next-token-issuer)

(defgiven "Google's JWKS is unreachable"
  isaac.google-steps/google-jwks-unreachable)

(defgiven "Google's JWKS misses the kid then serves it"
  isaac.google-steps/google-jwks-misses-then-serves)

(defthen "Google's JWKS was fetched {n:int} times"
  isaac.google-steps/google-jwks-fetched-times)

(defwhen "Google pushes {n:int} messages with forged tokens"
  isaac.google-steps/google-pushes-forged)

(defgiven #"a fixture route (\w+) (/[^\s]+) requires scope :([^\s]+)$"
  isaac.google-steps/fixture-route-unquoted)

(defwhen #"Google pushes message \"([^\"]+)\" of type \"([^\"]+)\" with data:"
  isaac.google-steps/google-pushes)

(defgiven "the Google runtime component is started"
  isaac.google-steps/google-runtime-component-starts)

(defwhen #"Google pushes message \"([^\"]+)\" of type \"([^\"]+)\" signed by \"([^\"]+)\" on \"([^\"]+)\" with data:"
  isaac.google-steps/google-pushes-signed-on)

(defwhen #"Google pushes a Gmail watch message \"([^\"]+)\" with data:"
  isaac.google-steps/google-pushes-gmail)

(defwhen #"Google pushes to GET (/[^\"]*)"
  isaac.google-steps/google-pushes-to)

(defwhen "the inbox worker ticks"
  isaac.google-steps/inbox-worker-ticks)

(defthen #"the skybeam handler received message \"([^\"]+)\""
  isaac.google-steps/skybeam-received)

(defthen #"the skybeam handler received message \"([^\"]+)\" once"
  isaac.google-steps/skybeam-received-once)

(defthen "the skybeam handler received no messages"
  isaac.google-steps/skybeam-received-none)

(defthen #"the longwave handler received message \"([^\"]+)\""
  isaac.google-steps/longwave-received)

(defthen #"the inbox holds (\d+) record for message \"([^\"]+)\""
  isaac.google-steps/inbox-holds)

;; region ----- Workspace Events / registration timer -----

(defn- space-target [space]
  (str "//chat.googleapis.com/" space))

(defn- remote-space [sub]
  (or (:key sub)
      (second (re-find #"googleapis\.com/(.*)$" (str (:targetResource sub))))))

(defn- record-events-http! [method url headers body query]
  (let [req (cond-> {:url     url
                     :method  (str/upper-case (name method))
                     :headers (or headers {})
                     :body    body
                     :stream  false
                     :key     "value"}
              query (assoc :query query :query-params query))]
    (g/update! :outbound-http-requests (fn [prior] (vec (conj (or prior []) req))))
    (g/assoc! :outbound-http-request req)
    req))

(defn- stub-events-request! [{:keys [method url headers body query]}]
  (let [headers (or headers
                    (try {"Authorization" (str "Bearer " (google-token/token))}
                         (catch Exception _ {"Authorization" "Bearer at-1"})))]
    (when (not= :get method)
      (record-events-http! method url headers body query)))
  (let [st @events-state*]
    (cond
      (and (= :get method) (str/ends-with? url "/subscriptions"))
      {:subscriptions (vec (vals (:subs st)))}

      (and (= :post method) (str/ends-with? url "/subscriptions"))
      (let [space  (second (re-find #"googleapis\.com/(.*)$" (str (:targetResource body))))
            reject (get-in st [:rejects space])]
        (if reject
          {:error   :api-error
           :status  (:status reject)
           :message (:message reject)
           :body    {:error {:message (:message reject) :status (:status reject)}}}
          (let [name (str "subscriptions/s-" (or (second (re-find #"spaces/(.+)$" (or space ""))) "new"))
                sub  {:name            name
                      :targetResource  (:targetResource body)
                      :expireTime      (:grant-expires st)
                      :eventTypes      (:eventTypes body)
                      :notificationEndpoint (:notificationEndpoint body)
                      :payloadOptions  (:payloadOptions body)}]
            (swap! events-state* assoc-in [:subs space] (assoc sub :key space))
            sub)))

      (= :patch method)
      (let [name (second (re-find #"/v1/(subscriptions/.+)$" url))
            sub  (some (fn [[_ s]] (when (= name (:name s)) s)) (:subs st))]
        (let [updated (assoc (or sub {}) :expireTime (:grant-expires st) :ttl (:ttl body))]
          (when-let [k (or (:key sub) (remote-space sub))]
            (swap! events-state* assoc-in [:subs k] (assoc updated :key k)))
          updated))

      (= :delete method)
      (let [name (second (re-find #"/v1/(subscriptions/.+)$" url))]
        (swap! events-state* update :subs
               (fn [subs] (into {} (remove (fn [[_ s]] (= name (:name s))) subs))))
        {})

      :else {})))

(defn workspace-events-has-no-subscriptions []
  (swap! events-state* assoc :subs {}))

(defn workspace-events-has-subscription [name space ts]
  (swap! events-state* assoc-in [:subs space]
         {:name           name
          :key            space
          :targetResource (space-target space)
          :expireTime     ts}))

(defn workspace-events-grants [ts]
  (swap! events-state* assoc :grant-expires ts))

(defn workspace-events-rejects [space status message]
  (let [status (if (string? status) (parse-long status) status)]
    (swap! events-state* assoc-in [:rejects space] {:status status :message message})))

(defn- health-space-keys []
  (vec (sort (keys (:subs @events-state*)))))

(defn- ensure-health-registration! []
  (when (and (empty? (google-registration/all)) (seq (health-space-keys)))
    (google-registration/register!
      [:health-fixture {:create! (fn [key] (google-events/create-subscription!
                                             {:targetResource (space-target key)}))
                        :renew!  (fn [name] (google-events/renew-subscription! name))
                        :expiry  (fn [sub] (or (:expireTime sub) (:expires-at sub)))
                        :key     health-space-keys}])))

(defn google-registration-timer-ticks []
  (when-let [inject (try (requiring-resolve 'isaac.gchat-steps/inject-gchat-module!)
                         (catch Exception _ nil))]
    (inject))
  (ensure-health-registration!)
  (let [now  (or (g/get :current-time) (memory/now))
        fs*  (feature-fs)
        root (feature-root)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (with-redefs [google-events/request! stub-events-request!
                    memory/now (constantly now)]
        (binding [memory/*now* now]
          (google-registration/tick! {:now      now
                                      :root     root
                                      :door-up? (boolean (g/get :server-handler-opts))}))))))

(defn no-outbound-http-to [url]
  (let [reqs (or (g/get :outbound-http-requests) [])]
    (g/should= [] (filterv #(= url (:url %)) reqs))))

(defn outbound-http-count-for-space [n url space]
  (let [n    (if (string? n) (parse-long n) n)
        reqs (or (g/get :outbound-http-requests) [])
        hits (filter (fn [r]
                       (and (= url (:url r))
                            (or (= (space-target space) (get-in r [:body :targetResource]))
                                (str/includes? (str (get-in r [:body :targetResource])) space))))
                     reqs)]
    (g/should= n (count hits))))

(defn test-clock-advances [n]
  (let [n   (if (string? n) (parse-long n) n)
        now (or (g/get :current-time) (java.time.Instant/now))]
    (g/assoc! :current-time (.plusMillis now n))))

(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (with-redefs [google-events/request! stub-events-request!]
      (thunk))))

(defgiven "the Workspace Events API has no subscriptions"
  isaac.google-steps/workspace-events-has-no-subscriptions)

(defgiven #"the Workspace Events API has subscription \"([^\"]+)\" for \"([^\"]+)\" expiring at \"([^\"]+)\""
  isaac.google-steps/workspace-events-has-subscription)

(defgiven #"the Workspace Events API grants subscriptions expiring at \"([^\"]+)\""
  isaac.google-steps/workspace-events-grants)

(defgiven #"the Workspace Events API rejects creates for \"([^\"]+)\" with (\d+) \"([^\"]+)\""
  isaac.google-steps/workspace-events-rejects)

(defwhen "the google registration timer ticks"
  isaac.google-steps/google-registration-timer-ticks)

(defn last-google-event-was-at [key ts]
  (let [fs*  (feature-fs)
        root (feature-root)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (google-health/record-last-event! root key ts))))

(defgiven #"the last Google event for \"([^\"]+)\" was at \"([^\"]+)\""
  isaac.google-steps/last-google-event-was-at)

(defn google-heartbeat-arrived
  "What the door does when the tick's own synthetic message comes back: it
   records the heartbeat, and nothing else — a heartbeat is not an event
   (isaac-an14)."
  [tenant ts]
  (let [fs*  (feature-fs)
        root (feature-root)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (google-health/record-heartbeat! root (keyword tenant) ts))))

(defn heartbeat-published-to
  "Exactly one heartbeat left for this topic: a real Pub/Sub publish, marked
   so the door can tell it from Chat traffic."
  [topic]
  (let [url  (str "https://pubsub.googleapis.com/v1/" topic ":publish")
        reqs (or (g/get :outbound-http-requests) [])
        hits (filter (fn [r]
                       (and (= url (:url r))
                            (= google-heartbeat/CE-TYPE
                               (get-in r [:body :messages 0 :attributes "ce-type"]))))
                     reqs)]
    (g/should= 1 (count hits))))

(defwhen #"a Google heartbeat for \"([^\"]+)\" arrived at \"([^\"]+)\""
  isaac.google-steps/google-heartbeat-arrived)

(defthen #"a Google heartbeat was published to \"([^\"]+)\""
  isaac.google-steps/heartbeat-published-to)

(defwhen "the test clock advances {n:int} milliseconds"
  isaac.google-steps/test-clock-advances)

(defthen #"no outbound HTTP request to \"([^\"]+)\" was made"
  isaac.google-steps/no-outbound-http-to)

(defthen #"(\d+) outbound HTTP requests to \"([^\"]+)\" for \"([^\"]+)\" were made"
  isaac.google-steps/outbound-http-count-for-space)

;; region ----- People API (who spoke) -----

(defn- person-key [user]
  (last (str/split (str user) #"/")))

(defn- people-url? [url]
  (str/includes? (str url) "people.googleapis.com"))

(defn- stub-people-request! [{:keys [url query] :as request}]
  (if-not (people-url? url)
    ;; anything else still belongs to whoever owned the seam before us
    ((or @original-events-request* (constantly {})) request)
    (do
      (g/update! :people-requests
                 (fn [prior] (vec (conj (or prior []) {:url (str url) :query query}))))
      (let [state @people-state*]
        (or (:error state)
            (get (:known state) (person-key url))
            {})))))

(defn- install-people-stub!
  "Own isaac.google.events/request! for the scenario so any module's code path
   (gchat's inbound handler, say) sees the stubbed People API, not just steps
   that wrap it themselves."
  []
  (when-not @original-events-request*
    (reset! original-events-request* google-events/request!)
    (alter-var-root #'google-events/request! (constantly stub-people-request!))))

(defn restore-people-stub! []
  (when-let [original @original-events-request*]
    (alter-var-root #'google-events/request! (constantly original))
    (reset! original-events-request* nil)))

(defn people-api-knows [user display-name email]
  (install-people-stub!)
  (swap! people-state* assoc-in [:known (person-key user)]
         {:names          [{:displayName display-name :metadata {:primary true}}]
          :emailAddresses [{:value email :metadata {:primary true}}]}))

(defn people-api-refuses [status message]
  (install-people-stub!)
  (swap! people-state* assoc :error
         {:error   :api-error
          :status  (if (string? status) (parse-long status) status)
          :message message}))

(defn- resolve-person! [user display-name]
  (let [entry (atom nil)]
    (log/capture-logs
      (with-redefs [google-events/request! stub-people-request!]
        (reset! entry (google-people/resolve user (cond-> {}
                                                    (seq (str (or display-name "")))
                                                    (assoc :display-name display-name))))))
    (g/assoc! :person @entry)
    (g/update! :people-warnings
               (fn [prior]
                 (vec (concat (or prior [])
                              (filterv #(= :warn (:level %)) @log/captured-logs)))))))

(defn person-is-resolved [user]
  (resolve-person! user nil))

(defn person-is-resolved-with-display-name [user display-name]
  (resolve-person! user display-name))

(defn person-renders-as [expected]
  (g/should= expected (google-people/render (g/get :person))))

(defn person-has-no-email []
  (g/should= nil (:email (g/get :person))))

(defn people-api-asked [n user fields]
  (let [n     (if (string? n) (parse-long n) n)
        hits  (filter (fn [{:keys [url query]}]
                        (and (str/ends-with? url (str "/people/" (person-key user)))
                             (= fields (:personFields query))))
                      (or (g/get :people-requests) []))]
    (g/should= n (count hits))))

(defn one-scope-warning [scope]
  (let [warns (filter #(= :google.people/scope-missing (:event %))
                      (or (g/get :people-warnings) []))]
    (g/should= 1 (count warns))
    (g/should= scope (:scope (first warns)))))

(defgiven #"the Google People API knows \"([^\"]+)\" as \"([^\"]+)\" with email \"([^\"]+)\""
  isaac.google-steps/people-api-knows)

(defgiven #"the Google People API refuses with (\d+) \"([^\"]+)\""
  isaac.google-steps/people-api-refuses)

(defwhen #"the person \"([^\"]+)\" is resolved with display name \"([^\"]+)\""
  isaac.google-steps/person-is-resolved-with-display-name)

(defwhen #"the person \"([^\"]+)\" is resolved$"
  isaac.google-steps/person-is-resolved)

(defthen #"the person renders as \"([^\"]+)\""
  isaac.google-steps/person-renders-as)

(defthen "the person has no email"
  isaac.google-steps/person-has-no-email)

(defthen #"the People API was asked (\d+) times? for \"([^\"]+)\" with fields \"([^\"]+)\""
  isaac.google-steps/people-api-asked)

(defthen #"exactly one warning named the missing \"([^\"]+)\" scope"
  isaac.google-steps/one-scope-warning)
