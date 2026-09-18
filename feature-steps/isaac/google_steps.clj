(ns isaac.google-steps
  "Module-owned Google login feature steps. One helper each — everything
   else is existing foundation/agent steps."
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.fs :as fs]
    [isaac.google.token :as google-token]
    [isaac.llm.auth.store :as auth-store]
    [isaac.llm.http :as llm-http]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]))

(helper! isaac.google-steps)

(g/after-scenario
  (fn []
    (alter-var-root #'discovery/*foundation-index-override* (constantly nil))))

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
