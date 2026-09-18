(ns isaac.google-steps
  "Module-owned Google login feature steps. One helper each — everything
   else is existing foundation/agent steps."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.fs :as fs]
    [cheshire.core :as json]
    [isaac.google.events :as google-events]
    [isaac.google.handler :as google-handler]
    [isaac.google.identity :as google-identity]
    [isaac.google.inbox :as google-inbox]
    [isaac.google.registration :as google-registration]
    [isaac.google.token :as google-token]
    [isaac.google.worker :as google-worker]
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

(g/after-scenario
  (fn []
    (alter-var-root #'discovery/*foundation-index-override* (constantly nil))
    (reset! google-identity/skip-signature?* false)
    (reset! events-state* default-events-state)
    (google-registration/reset-registrations!)))

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- feature-root []
  (or (g/get :root) "target/test-state"))

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
      (let [tokens (auth-store/load-tokens (feature-root) "google" (feature-fs))]
        (if (or tokens (g/get :google-token-stub))
          (do
            (g/should tokens)
            (g/should= at (:access tokens))
            (g/should= rt (:refresh tokens)))
          (auth-store/save-tokens! (feature-root) "google"
                                   {:access_token  at
                                    :refresh_token rt
                                    :expires_in    3600}
                                   (feature-fs)))))))

(defn google-auth-store-has-expired-access [rt]
  (with-feature-fs
    (fn []
      (auth-store/save-tokens! (feature-root) "google"
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

(def ^:private received* (atom {}))
(def ^:private throwers* (atom #{}))
(def ^:private next-claims* (atom nil))

(defn- b64url [bytes]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))

(defn- mint-token [claims]
  (let [header  (b64url (.getBytes "{\"alg\":\"none\"}" "UTF-8"))
        payload (b64url (.getBytes (json/generate-string claims) "UTF-8"))]
    (str header "." payload ".")))

(defn- default-claims []
  {:aud   "https://isaac.example/google/pubsub"
   :email "pubsub-push@marigold.iam.gserviceaccount.com"})

(defn- handler-for [name]
  (fn [event]
    (when (contains? @throwers* name)
      (throw (ex-info (str name " boom") {:handler name})))
    (swap! received* update name (fnil conj []) event)))

(defn google-signs-with-test-key []
  (reset! google-identity/skip-signature?* true)
  (reset! next-claims* nil)
  (reset! received* {})
  (reset! throwers* #{}))

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

(defn- push-envelope [id ce-type data]
  {:message {:messageId  id
             :data       (.encodeToString (Base64/getEncoder) (.getBytes (or data "{}") "UTF-8"))
             :attributes (cond-> {}
                           ce-type (assoc "ce-type" ce-type))}})

(defn google-pushes [id ce-type data]
  (let [claims (merge (default-claims) @next-claims*)
        token  (if (:unsigned? claims) "not-a-jwt" (mint-token (dissoc claims :unsigned?)))
        _      (reset! next-claims* nil)
        body   (json/generate-string (push-envelope id ce-type data))]
    ((requiring-resolve 'isaac.http.server-steps/post-request-with-header-and-body)
     "/google/pubsub"
     (str "Authorization: Bearer " token)
     body)))

(defn google-pushes-gmail [id data]
  (google-pushes id nil data))

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

(defgiven #"a fixture route (\w+) (/[^\s]+) requires scope :([^\s]+)$"
  isaac.google-steps/fixture-route-unquoted)

(defwhen #"Google pushes message \"([^\"]+)\" of type \"([^\"]+)\" with data:"
  isaac.google-steps/google-pushes)

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

(defn google-registration-timer-ticks []
  (when-let [inject (requiring-resolve 'isaac.gchat-steps/inject-gchat-module!)]
    (inject))
  (let [now  (or (g/get :current-time) (memory/now))
        fs*  (feature-fs)
        root (feature-root)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (with-redefs [google-events/request! stub-events-request!
                    memory/now (constantly now)]
        (binding [memory/*now* now]
          (google-registration/tick! {:now now :root root}))))))

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

(defwhen "the test clock advances {n:int} milliseconds"
  isaac.google-steps/test-clock-advances)

(defthen #"no outbound HTTP request to \"([^\"]+)\" was made"
  isaac.google-steps/no-outbound-http-to)

(defthen #"(\d+) outbound HTTP requests to \"([^\"]+)\" for \"([^\"]+)\" were made"
  isaac.google-steps/outbound-http-count-for-space)
