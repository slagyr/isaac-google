(ns isaac.google.cli
  "`isaac google login` and `isaac google status`.

   Both are per organization: each has its own OAuth client and its own
   Google user, so its tokens are stored under its own provider key and
   `--tenant` says which one is signing in. A host with one organization may
   leave `--tenant` off — there is only one to mean; a host with several sees
   its status grouped by organization (isaac-okfj)."
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
    [isaac.google.tenants :as tenants]
    [isaac.llm.auth.store :as auth-store]
    [isaac.nexus :as nexus]))

(def REDIRECT-URI "http://localhost:1/")

(def option-spec
  [[nil "--code CODE" "Authorization code pasted from the Google consent screen"]
   [nil "--tenant TENANT" "Google organization to sign in as (default: the only one configured)"]
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

(defn- tenant-id
  "Which organization this invocation is for: --tenant when given, else the
   only one configured. nil when the host must say which."
  [config opts]
  (let [named (some-> (:tenant opts) str str/trim not-empty keyword)]
    (tenants/resolve-id config named)))

(defn- oauth-from [config id]
  (:oauth (tenants/tenant-config config id)))

(defn- client-id-key
  "Config key a human would set for this organization's OAuth client."
  [config id]
  (str/join "." (concat (map name (tenants/config-path config (or id :<organization>)))
                        ["oauth" "client-id"])))

(defn- missing-client-id? [oauth]
  (str/blank? (:client-id oauth)))

(defn- print-auth-url! [config id]
  (let [oauth (oauth-from config id)
        url   (oauth/authorization-url {:client-id    (:client-id oauth)
                                        :scopes       (scopes/union)
                                        :redirect-uri REDIRECT-URI
                                        :state        "isaac-google"})]
    (println (str "Open this URL, then paste the code with `isaac google login "
                  "--tenant " (name id) " --code <code>`:"))
    (println url)
    0))

(defn- exchange-and-store! [opts config id code]
  (let [oauth  (oauth-from config id)
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
        (auth-store/save-tokens! (derive-root opts) (tenants/auth-provider id) tokens (feature-fs opts))
        (println (str "Signed in as " (or (:account oauth) "the Google user")))
        (when (oauth/seven-day-token? (:expires_in tokens))
          (println "Google returned a 7-day refresh token — the consent screen is still in Testing."))
        0))))

(defn- run-login [opts code]
  (try
    (let [config (load-cfg opts)
          id     (tenant-id config opts)
          oauth  (oauth-from config id)]
      (cond
        (missing-client-id? oauth)
        (do (binding [*out* *err*]
              (println (str (client-id-key config id)
                            " is required. Set it in config (google.edn or isaac.edn).")))
            1)

        (str/blank? code)
        (print-auth-url! config id)

        :else
        (exchange-and-store! opts config id code)))
    (catch clojure.lang.ExceptionInfo e
      (let [errors (:errors (ex-data e))
            msg    (or (some (fn [{:keys [key value]}]
                               (when (and key (str/includes? (str key) "oauth.client-id"))
                                 (str key " " value)))
                             errors)
                       (ex-message e)
                       (client-id-key {} nil))]
        (binding [*out* *err*]
          (println msg)
          (when (and (seq errors) (not (str/includes? msg "oauth.client-id")))
            (doseq [{:keys [key value]} errors]
              (println (str key " " value)))))
        1))
    (catch Exception e
      (binding [*out* *err*]
        (println (or (.getMessage e) (client-id-key {} nil))))
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


(defn- tenant-status-lines
  "One organization's status, seen as that organization: its registrations
   listed with its token, its keys from its own comms."
  [root fs* state id]
  (binding [tenants/*tenant* id]
    (let [configured (nexus/-with-nested-nexus {:fs fs* :root root} (configured-keys))
          listed (try
                   (or (:subscriptions (events/list-subscriptions!)) [])
                   (catch Exception _ []))
          ;; Entries that bring their own :remote (the Gmail watch — Google
          ;; cannot list it) are merged over the Workspace Events listing.
          own    (nexus/-with-nested-nexus {:fs fs* :root root}
                   (apply merge {}
                          (for [[_ entry] (registration/all)
                                :when (fn? (:remote entry))]
                            (try ((:remote entry)) (catch Exception _ {})))))
          remote (merge (or (not-empty (remote-index listed))
                            (registration/load-state root))
                        own)
          keys   (vec (or (seq configured)
                          (sort (keys (or (:last-event-at state) {})))
                          (sort (keys remote))))]
      (health/status-lines {:keys   keys
                            :remote remote
                            :state  state}))))

(defn- run-status [opts]
  (try
    (let [root    (derive-root opts)
          fs*     (feature-fs opts)
          config  (try (load-cfg opts) (catch Exception _ {}))
          state   (nexus/-with-nested-nexus {:fs fs* :root root}
                    (health/load-state root))
          ids     (tenants/ids config)
          several (> (count ids) 1)]
      (when (empty? ids)
        (println "No Google organization configured. Set google.<organization>.oauth.client-id."))
      (doseq [id ids]
        (when several
          (println (str "tenant: " (name id))))
        (doseq [line (tenant-status-lines root fs* state id)]
          (println (if several (str "  " line) line))))
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
  [{:name "login"  :summary "Sign in as the Google user (authorization-code paste; --tenant for one of several)"}
   {:name "status" :summary "Show Google registrations, expiries, last event, and door"}])
