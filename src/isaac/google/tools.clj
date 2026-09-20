(ns isaac.google.tools
  "Google tools the agent can call with Isaac's own token.

   Before these, a crew that needed to know who someone was shelled out to a
   separate CLI with its own OAuth grant. A tool here uses Isaac's token,
   Isaac's scopes and the crew's tool allow-list — deterministic, auditable,
   and revoked the moment the login is (isaac-jqk2)."
  (:require
    [clojure.string :as str]
    [isaac.google.events :as events]
    [isaac.google.people :as people]))

(def PEOPLE-BASE "https://people.googleapis.com/v1")

(defn- error [message] {:isError true :error message})

(defn- args-of [arguments]
  (reduce-kv (fn [m k v] (assoc m (str/lower-case (name k)) v)) {} (or arguments {})))

(defn search-directory!
  "HTTP seam: People API searchDirectoryPeople for an email address."
  [query]
  (events/request! {:method :get
                    :url    (str PEOPLE-BASE "/people:searchDirectoryPeople")
                    :query  {:query      (str query)
                             :readMask   "names,emailAddresses"
                             :sources    "DIRECTORY_SOURCE_TYPE_DOMAIN_PROFILE"
                             :pageSize   5}}))

(defn- person->entry [person]
  {:user         (:resourceName person)
   :display-name (some :displayName (:names person))
   :email        (some :value (:emailAddresses person))})

(defn whois
  "users/<id> → who that is; an email → which users/<id> that is."
  [arguments]
  (let [args (args-of arguments)
        who  (some-> (get args "who") str str/trim)]
    (cond
      (str/blank? who)
      (error "who is required: a users/<id> or an email address")

      (str/includes? who "@")
      (let [response (search-directory! who)]
        (if (:error response)
          (error (str "directory lookup failed: " (:message response)))
          (if-let [person (first (keep :person (:people response)))]
            {:result (person->entry person)}
            {:result {:email who :display-name nil :user nil}})))

      :else
      (if-let [entry (people/resolve who)]
        {:result (select-keys entry [:user :display-name :email])}
        (error (str "no such person: " who))))))

(defn whois-tool-factory [_]
  {:description (str "Who is this person? Give a Google Chat users/<id> and get their name and "
                     "email; give an email and get their users/<id>. Read-only, through the "
                     "Workspace directory with Isaac's own token.")
   :parameters  {:type       "object"
                 :properties {"who" {:type        "string"
                                     :description "A users/<id> resource name or an email address"}}
                 :required   ["who"]}
   :handler     #'whois})
