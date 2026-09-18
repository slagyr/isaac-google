(ns isaac.google.cli
  (:require
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.cli.api :as cli-api]
    [isaac.cli.common :as cli-common]
    [isaac.cli.registry :as cli]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.google.events :as events]
    [isaac.google.health :as health]
    [isaac.google.oauth :as oauth]
    [isaac.google.registration :as registration]
    [isaac.google.scopes :as scopes]
    [isaac.llm.auth.store :as auth-store]
    [isaac.nexus :as nexus]))

(def PROVIDER "google")
(def REDIRECT-URI "http://localhost:1/")

(def option-spec
  [[nil "--code CODE" "Authorization code pasted from the Google consent screen"]
   ["-h" "--help" "Show help"]])

(defn- derive-root [opts]
  (or (:root opts)
      (nexus/get :root)
      (root/current-root)
      (root/default-root opts)))

(defn- feature-fs [opts]
  (or (:fs opts) (fs/instance) (fs/real-fs)))

(defn- load-cfg [opts]
  (let [root (derive-root opts)
        fs*  (feature-fs opts)]
    (or (when (seq (dissoc (:config opts) :root))
          (:config opts))
        (loader/load-config! root fs* "google cli"))))

(defn- oauth-from [config]
  (get-in config [:google :oauth]))

(defn- missing-client-id? [oauth]
  (str/blank? (:client-id oauth)))

(defn- print-auth-url! [config]
  (let [oauth (oauth-from config)
        url   (oauth/authorization-url {:client-id    (:client-id oauth)
                                        :scopes       (scopes/union)
                                        :redirect-uri REDIRECT-URI
                                        :state        "isaac-google"})]
    (println "Open this URL, then paste the code with `isaac google login --code <code>`:")
    (println url)
    0))

(defn- exchange-and-store! [opts config code]
  (let [oauth  (oauth-from config)
        tokens (oauth/exchange-code! (assoc oauth :redirect-uri REDIRECT-URI) code)]
    (cond
      (:error tokens)
      (do (binding [*out* *err*]
            (println (str "Google token exchange failed: "
                          (or (:message tokens) (:error tokens)))))
          1)

      (not (:access_token tokens))
      (do (binding [*out* *err*]
            (println "Google token exchange succeeded but no access_token in response"))
          1)

      :else
      (do
        (auth-store/save-tokens! (derive-root opts) PROVIDER tokens (feature-fs opts))
        (println (str "Signed in as " (or (:account oauth) "the Google user")))
        (when (oauth/seven-day-token? (:expires_in tokens))
          (println "Google returned a 7-day refresh token — the consent screen is still in Testing."))
        0))))

(defn- run-login [opts code]
  (try
    (let [config (load-cfg opts)
          oauth  (oauth-from config)]
      (cond
        (missing-client-id? oauth)
        (do (binding [*out* *err*]
              (println "google.oauth.client-id is required. Set it in config (google.edn or isaac.edn)."))
            1)

        (str/blank? code)
        (print-auth-url! config)

        :else
        (exchange-and-store! opts config code)))
    (catch clojure.lang.ExceptionInfo e
      (let [errors (:errors (ex-data e))
            msg    (or (some (fn [{:keys [key value]}]
                               (when (and key (str/includes? (str key) "google.oauth.client-id"))
                                 (str key " " value)))
                             errors)
                       (ex-message e)
                       "google.oauth.client-id")]
        (binding [*out* *err*]
          (println msg)
          (when (and (seq errors) (not (str/includes? msg "google.oauth.client-id")))
            (doseq [{:keys [key value]} errors]
              (println (str key " " value)))))
        1))
    (catch Exception e
      (binding [*out* *err*]
        (println (or (.getMessage e) "google.oauth.client-id")))
      1)))

(defn- parse-options [raw-args]
  (tools-cli/parse-opts raw-args option-spec))

(defn- remote-key [sub]
  (let [target (str (or (:targetResource sub) ""))]
    (or (second (re-find #"googleapis\.com/(.*)$" target))
        (:key sub))))

(defn- remote-index [subscriptions]
  (into {}
        (keep (fn [sub]
                (when-let [k (remote-key sub)]
                  [k {:name       (:name sub)
                      :expires-at (or (:expireTime sub) (:expires-at sub))
                      :raw        sub}]))
              subscriptions)))

(defn- configured-keys []
  (registration/reset-registrations!)
  (#'registration/ensure-contributions!)
  (vec (mapcat (fn [[_ entry]]
                 (let [f (:key entry)]
                   (cond
                     (fn? f) (or (f) [])
                     (sequential? f) f
                     :else [])))
               (registration/all))))


(defn- run-status [opts]
  (try
    (let [root   (derive-root opts)
          fs*    (feature-fs opts)
          state  (nexus/-with-nested-nexus {:fs fs* :root root}
                   (health/load-state root))
          listed (try
                   (or (:subscriptions (events/list-subscriptions!)) [])
                   (catch Exception _ []))
          remote (or (not-empty (remote-index listed))
                     (registration/load-state root))
          keys   (vec (or (seq (configured-keys))
                          (sort (keys (or (:last-event-at state) {})))
                          (sort (keys remote))))
          lines  (health/status-lines {:keys   keys
                                       :remote remote
                                       :state  state})]
      (doseq [line lines]
        (println line))
      0)
    (catch Exception e
      (binding [*out* *err*]
        (println (or (.getMessage e) "google status failed")))
      1)))

(defn run-fn [opts]
  (let [raw-args (or (:_raw-args opts) [])
        subcmd   (first raw-args)]
    (cond
      (or (nil? subcmd) (= "--help" subcmd) (= "-h" subcmd))
      (do (println (cli/command-help (cli/get-command "google"))) 0)

      (= "login" subcmd)
      (cli-common/standard-run-fn
        "google"
        parse-options
        (fn [merged] (run-login merged (:code merged)))
        (assoc opts :_raw-args (vec (rest raw-args))))

      (= "status" subcmd)
      (run-status opts)

      :else
      (do (binding [*out* *err*]
            (println (str "Unknown google subcommand: " subcmd)))
          1))))

(defmethod cli-api/run :google [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :google [_id]
  option-spec)

(defmethod cli-api/subcommands :google [_id]
  [{:name "login"  :summary "Sign in as the Google user (authorization-code paste)"}
   {:name "status" :summary "Show Google registrations, expiries, last event, and door"}])
