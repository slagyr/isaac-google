(ns isaac.google.tenants
  "One Isaac host, several Google organizations.

   Everything Google is per organization: the GCP project, the Pub/Sub topic,
   the push service account, the OAuth client, and the Google user Isaac signs
   in as. A tenant is that complete set, not a namespace over one login.

   A deployment with one organization writes the flat map it always wrote
   (`:google {:project ...}`) and reads as the single tenant `:default`;
   nothing about that deployment changes. A deployment with several writes
   `:google {:tonotop {...} :acme {...}}` (isaac-1zkz)."
  (:require
    [clojure.string :as str]))

(def DEFAULT
  "Tenant id of a flat, single-organization `:google` config."
  :default)

(def ^:private tenant-fields
  "Keys of one tenant's own config. Their presence is what tells a flat map
   from a map of tenant id -> tenant."
  #{:project :topic :oauth :push :health :renew-within-hours})

(def ^:dynamic *tenant*
  "Tenant the current thread is acting as. The push door binds it for a
   handler; a comm binds it for a send."
  nil)

(defn- flat? [slice]
  (boolean (some tenant-fields (keys slice))))

(defn tenants
  "config -> {tenant-id tenant-config}. A flat `:google` map reads as
   `{:default <that map>}`."
  [config]
  (let [slice (:google config)]
    (cond
      (not (map? slice)) {}
      (empty? slice) {}
      (flat? slice) {DEFAULT slice}
      :else (into {} (filter (comp map? val)) slice))))

(defn ids
  "Every configured tenant id, sorted."
  [config]
  (vec (sort (keys (tenants config)))))

(defn tenant-config
  "One tenant's complete set, or nil when nobody declared it."
  [config id]
  (get (tenants config) id))

(defn config-path
  "Path into the live config at which `id`'s settings sit. A flat config keeps
   `[:google]` so the config refs written before tenants still resolve."
  [config id]
  (let [slice (:google config)]
    (if (and (= DEFAULT id)
             (or (not (map? slice)) (empty? slice) (flat? slice)))
      [:google]
      [:google id])))

(defn resolve-id
  "Which tenant to act as: the one named, else the one bound to this thread,
   else the only one configured, else :default."
  ([config] (resolve-id config nil))
  ([config id]
   (or id
       *tenant*
       (let [configured (ids config)]
         (if (= 1 (count configured))
           (first configured)
           DEFAULT)))))

(defn auth-provider
  "Auth-store provider key for a tenant's tokens. The default tenant keeps the
   plain \"google\" key, so a single-organization host's existing login stands."
  [id]
  (if (or (nil? id) (= DEFAULT id))
    "google"
    (str "google/" (name id))))

(defn- subscription-project [subscription]
  (second (re-find #"^projects/([^/]+)/" (str subscription))))

(defn tenant-for-subscription
  "Which tenant a Pub/Sub push came from, by the project in its subscription
   name. nil when no configured tenant owns that project."
  [config subscription]
  (when-let [project (subscription-project subscription)]
    (some (fn [[id tenant]]
            (when (= project (str (:project tenant))) id))
          (sort-by key (tenants config)))))

(defn principal-tenant
  "Which tenant a door principal speaks for. The flat host's principal is the
   plain `:google-pubsub`; a tenanted host's is `:google-pubsub/<tenant>`."
  [principal-name]
  (when principal-name
    (if (namespace principal-name)
      (keyword (name principal-name))
      (when (= "google-pubsub" (name principal-name)) DEFAULT))))

(defn tenant-of-push
  "Which tenant owns this push: `{:tenant id}`, or
   `{:refused :tenant-mismatch ...}` when the subscription it arrived on and
   the service account that signed it belong to different organizations.

   Two independent claims — the project in the subscription name and the
   principal the token proved — must agree. A host that configured no
   `:project` has only the principal to go on, which is the flat case."
  [config event principal-name]
  (let [by-subscription (tenant-for-subscription config (:subscription event))
        by-principal    (principal-tenant principal-name)]
    (cond
      (and by-subscription by-principal (not= by-subscription by-principal))
      {:refused          :tenant-mismatch
       :tenant           by-subscription
       :principal-tenant by-principal}

      by-subscription {:tenant by-subscription}
      by-principal    {:tenant by-principal}
      :else           {:tenant (resolve-id config nil)})))

(defn- comm-type
  "The comm kind a config entry declares, or the kind the conventional comm
   named for it is."
  [[name slice]]
  (or (some-> (:type slice) keyword) (keyword name)))

(defn comms
  "Every configured comm of one kind, as [comm-name slice], by name."
  [config kind]
  (vec (sort-by (comp str key)
                (filter (fn [entry]
                          (and (map? (val entry)) (= kind (comm-type entry))))
                        (:comms config)))))

(defn of-comm
  "Which organization a comm speaks for: the one its slice names with
   `:google`, else the only one configured, else the default tenant."
  [config slice]
  (if-let [named (:google slice)]
    (keyword named)
    (let [configured (ids config)]
      (if (= 1 (count configured))
        (first configured)
        DEFAULT))))

(defn comms-for
  "The comms of one kind that speak for one organization."
  [config kind id]
  (vec (filter (fn [[_ slice]] (= id (of-comm config slice)))
               (comms config kind))))

(defn subscription-of
  "The `subscription:` field of a Pub/Sub push body, whichever shape it arrives in."
  [body]
  (let [v (or (:subscription body) (get body "subscription"))]
    (when-not (str/blank? (str v)) (str v))))
