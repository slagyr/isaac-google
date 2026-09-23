(ns isaac.google.http-spec
  (:require
    [cheshire.core :as json]
    [isaac.fs :as fs]
    [isaac.google.health :as health]
    [isaac.google.heartbeat :as heartbeat]
    [isaac.google.http :as sut]
    [isaac.google.inbox :as inbox]
    [isaac.google.logins :as logins]
    [isaac.llm.auth.store :as auth-store]
    [isaac.llm.http :as llm-http]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)
    (java.util Base64)))

(def two-tenant-config
  {:google {:tonotop {:project "marigold"}
            :acme    {:project "acme-prod"}}})

(defn- b64-json [m]
  (.encodeToString (Base64/getEncoder) (.getBytes (json/generate-string m) "UTF-8")))

(defn- push-request [{:keys [id subscription principal config]}]
  {:isaac/principal (when principal {:name principal})
   :isaac/config    config
   :body            (json/generate-string
                      {:subscription subscription
                       :message      {:messageId id
                                      :data      (b64-json {:message {:name "spaces/AAA/messages/1"}})
                                      :attributes {"ce-type" "google.workspace.chat.message.v1.created"}}})})

(defn- heartbeat-request [{:keys [id subscription principal config]}]
  {:isaac/principal (when principal {:name principal})
   :isaac/config    config
   :body            (json/generate-string
                      {:subscription subscription
                       :message      {:messageId  id
                                      :data       (b64-json {:isaac-heartbeat true})
                                      :attributes {"ce-type" heartbeat/CE-TYPE}}})})

(describe "google push door"

  (around [example]
    (binding [memory/*now* (Instant/parse "2026-09-18T12:00:00Z")]
      (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
        (log/capture-logs (example)))))

  (context "one door, several organizations (isaac-1zkz)"

    (it "stamps the accepted event with the tenant that sent it"
      (let [response (sut/handler (push-request {:id           "m-1"
                                                 :subscription "projects/acme-prod/subscriptions/isaac"
                                                 :principal    :google-pubsub/acme
                                                 :config       two-tenant-config}))]
        (should= 204 (:status response))
        (should= [:acme] (mapv :tenant (inbox/pending "/test/isaac")))))

    (it "keeps each organization's push under its own tenant"
      (sut/handler (push-request {:id           "m-2"
                                  :subscription "projects/marigold/subscriptions/isaac"
                                  :principal    :google-pubsub/tonotop
                                  :config       two-tenant-config}))
      (sut/handler (push-request {:id           "m-3"
                                  :subscription "projects/acme-prod/subscriptions/isaac"
                                  :principal    :google-pubsub/acme
                                  :config       two-tenant-config}))
      (should= [:acme :tonotop]
               (sort (mapv :tenant (inbox/pending "/test/isaac")))))

    ;; Acme's service account proved itself, but the push arrived on tonotop's
    ;; subscription. One of the two claims is false; keep nothing.
    (it "refuses a push whose subscription and service account disagree"
      (let [response (sut/handler (push-request {:id           "m-4"
                                                 :subscription "projects/marigold/subscriptions/isaac"
                                                 :principal    :google-pubsub/acme
                                                 :config       two-tenant-config}))]
        (should= 403 (:status response))
        (should= [] (inbox/pending "/test/isaac"))
        (should= 1 (count (filter #(= :google/tenant-mismatch (:event %)) @log/captured-logs)))))

    ;; One organization is written the same way as several, so its pushes are
    ;; stamped with its own id — there is no default (isaac-okfj).
    (it "stamps a one-organization host's push with that organization"
      (sut/handler (push-request {:id           "m-5"
                                  :subscription "projects/marigold/subscriptions/isaac"
                                  :principal    :google-pubsub/tonotop
                                  :config       {:google {:tonotop {:project "marigold"}}}}))
      (should= [:tonotop] (mapv :tenant (inbox/pending "/test/isaac"))))

    ;; The flat shape isaac-1zkz accepted names no organization, so nothing on
    ;; this host could own the push. Refuse it rather than keep an event no
    ;; organization answers for (isaac-okfj).
    (it "refuses a push when no organization is configured"
      (let [response (sut/handler (push-request {:id           "m-6"
                                                 :subscription "projects/marigold/subscriptions/isaac"
                                                 :principal    :google-pubsub/tonotop
                                                 :config       {:google {:project "marigold"}}}))]
        (should= 401 (:status response))
        (should= [] (inbox/pending "/test/isaac"))
        (should= 1 (count (filter #(= :google/no-organization (:event %)) @log/captured-logs))))))

  ;; The tick's own synthetic message proves door, verification and inbox
  ;; without anyone talking. It is answered like any push and then stops:
  ;; nothing pending, no handler, no turn — and it is never mistaken for an
  ;; event, or it would quiet the very silence watch it serves (isaac-an14).
  (context "the synthetic heartbeat (isaac-an14)"

    (it "records the heartbeat and keeps nothing for the worker"
      (let [response (sut/handler (heartbeat-request {:id           "hb-1"
                                                      :subscription "projects/marigold/subscriptions/isaac"
                                                      :principal    :google-pubsub/tonotop
                                                      :config       two-tenant-config}))]
        (should= 204 (:status response))
        (should= [] (inbox/pending "/test/isaac"))
        (should= {:tonotop "2026-09-18T12:00:00Z"}
                 (:last-heartbeat-at (health/load-state "/test/isaac")))))

    (it "does not count as an event from Google"
      (sut/handler (heartbeat-request {:id           "hb-2"
                                       :subscription "projects/marigold/subscriptions/isaac"
                                       :principal    :google-pubsub/tonotop
                                       :config       two-tenant-config}))
      (let [state (health/load-state "/test/isaac")]
        (should= nil (:last-event-at state))
        (should= "2026-09-18T12:00:00Z" (:door-last-hit state)))))
  )

;; ---------------------------------------------------------------------------

(def login-config
  {:google {:tonotop {:oauth {:client-id     "isaac-test.apps.googleusercontent.com"
                              :client-secret "shh"
                              :account       "yopp@tonotop.com"}
                      :push  {:endpoint "https://isaac.example/google/pubsub"}}}})

(defn- callback-request [query]
  {:request-method :get
   :uri            "/google/oauth/callback"
   :query-string   query
   :isaac/config   login-config})

(defn- pending!
  ([state] (pending! state "2026-09-18T12:00:00Z"))
  ([state created-at]
   (logins/save! "/test/isaac" state {:tenant        :tonotop
                                      :scopes        ["openid"]
                                      :code-verifier "v-1"
                                      :created-at    created-at})))

(defn- stored-tokens []
  (auth-store/load-tokens "/test/isaac" "google/tonotop" (fs/instance)))

(describe "the Google OAuth callback door (isaac-2abl)"

  (around [example]
    (binding [memory/*now* (Instant/parse "2026-09-18T12:05:00Z")]
      (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
        (log/capture-logs (example)))))

  (context "the login the operator just finished"

    (it "exchanges the code as the organization that started the login"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [_url _headers body & _]
                                            (reset! captured body)
                                            {:access_token "at-1" :refresh_token "rt-1" :expires_in 3600})]
          (pending! "st-abcdefgh")
          (let [response (sut/oauth-callback (callback-request "state=st-abcdefgh&code=4%2F0AbCd"))]
            (should= 200 (:status response))
            (should-contain "Signed in as yopp@tonotop.com for organization tonotop" (:body response))
            (should-contain "You can close this tab" (:body response))))
        (should= "4/0AbCd" (:code @captured))
        (should= "isaac-test.apps.googleusercontent.com" (:client_id @captured))
        (should= "https://isaac.example/google/oauth/callback" (:redirect_uri @captured))
        (should= "v-1" (:code_verifier @captured))))

    (it "stores the tokens under that organization and spends the state"
      (with-redefs [llm-http/post-json! (fn [& _] {:access_token "at-1" :refresh_token "rt-1" :expires_in 3600})]
        (pending! "st-abcdefgh")
        (sut/oauth-callback (callback-request "state=st-abcdefgh&code=4%2F0AbCd"))
        (should= "at-1" (:access (stored-tokens)))
        (should= "rt-1" (:refresh (stored-tokens)))
        (should-be-nil (logins/pending "/test/isaac" "st-abcdefgh"))))

    (it "says the login finished, naming only the organization"
      (with-redefs [llm-http/post-json! (fn [& _] {:access_token "at-1" :refresh_token "rt-1" :expires_in 3600})]
        (pending! "st-abcdefgh")
        (sut/oauth-callback (callback-request "state=st-abcdefgh&code=4%2F0AbCd"))
        (let [entry (first (filter #(= :google/login-completed (:event %)) @log/captured-logs))]
          (should-not-be-nil entry)
          (should= :info (:level entry))
          (should= :tonotop (:tenant entry)))))

    ;; A code, a token or the PKCE verifier in a log line is a credential in a
    ;; log line. None of the three is ever written.
    (it "writes no code, token or verifier anywhere in the log"
      (with-redefs [llm-http/post-json! (fn [& _] {:access_token "at-1" :refresh_token "rt-1" :expires_in 3600})]
        (pending! "st-abcdefgh")
        (sut/oauth-callback (callback-request "state=st-abcdefgh&code=4%2F0AbCd"))
        (let [written (pr-str @log/captured-logs)]
          (should-not-contain "4/0AbCd" written)
          (should-not-contain "at-1" written)
          (should-not-contain "rt-1" written)
          (should-not-contain "v-1" written)))))

  (context "anything else that lands on the callback"

    (it "refuses a state no login is waiting under, and stores nothing"
      (let [response (sut/oauth-callback (callback-request "state=st-nosuchstate&code=4%2F0AbCd"))]
        (should= 400 (:status response))
        (should-contain "sign-in" (:body response))
        (should-be-nil (stored-tokens))))

    (it "refuses a state that is no state of ours"
      (should= 400 (:status (sut/oauth-callback (callback-request "state=..%2F..%2Fetc&code=4%2F0AbCd"))))
      (should-be-nil (stored-tokens)))

    (it "tells an operator whose consent screen went cold to start again"
      (pending! "st-abcdefgh" "2026-09-18T11:50:00Z")
      (let [response (sut/oauth-callback (callback-request "state=st-abcdefgh&code=4%2F0AbCd"))]
        (should= 410 (:status response))
        (should-contain "expired" (:body response))
        (should-be-nil (stored-tokens))
        (should-be-nil (logins/pending "/test/isaac" "st-abcdefgh"))))

    (it "names the failure when Google refused the consent"
      (pending! "st-abcdefgh")
      (let [response (sut/oauth-callback (callback-request "state=st-abcdefgh&error=access_denied"))]
        (should= 400 (:status response))
        (should-contain "access_denied" (:body response))
        (should-be-nil (stored-tokens))))

    (it "refuses a login for an organization this host no longer serves"
      (pending! "st-abcdefgh")
      (let [request  (assoc (callback-request "state=st-abcdefgh&code=4%2F0AbCd")
                            :isaac/config {:google {:acme {:oauth {:client-id "other"}}}})
            response (sut/oauth-callback request)]
        (should= 400 (:status response))
        (should-contain "tonotop" (:body response))
        (should-be-nil (stored-tokens))))

    (it "says so when Google refuses the exchange"
      (with-redefs [llm-http/post-json! (fn [& _] {:error "invalid_grant" :message "bad code"})]
        (pending! "st-abcdefgh")
        (let [response (sut/oauth-callback (callback-request "state=st-abcdefgh&code=4%2F0AbCd"))]
          (should= 502 (:status response))
          (should-contain "invalid_grant" (:body response))
          (should-be-nil (stored-tokens)))))

    ;; Whatever Google echoes back lands in a page this host serves, so it is
    ;; escaped before it is written.
    (it "escapes what Google echoed back into the page"
      (pending! "st-abcdefgh")
      (let [response (sut/oauth-callback (callback-request "state=st-abcdefgh&error=%3Cscript%3Ealert(1)%3C%2Fscript%3E"))]
        (should-not-contain "<script>" (:body response))
        (should-contain "&lt;script&gt;" (:body response)))))
  )
