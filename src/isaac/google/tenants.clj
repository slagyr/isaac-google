(ns isaac.google.tenants
  "One Isaac host, one or several Google organizations.

   Everything Google is per organization: the GCP project, the Pub/Sub topic,
   the push service account, the OAuth client, and the Google user Isaac signs
   in as. An organization is that complete set, not a namespace over one login.

   There is exactly one way to write it. `:google` is a map of organization id
   to that organization's config, whether the host serves one organization or
   ten:

       :google {:tonotop {:project \"tonotop-yopp\" :oauth {...} :push {...}}}

   The flat `:google {:project ...}` isaac-1zkz also accepted is gone: a second
   shape for the same thing left a reader guessing which one was meant, and
   made a working config report every nested field as an unknown key. A flat
   map is now a config error naming the shape, never a silent reinterpretation
   (isaac-okfj)."
  (:require
    [clojure.string :as str]))

(def organization-fields
  "Keys of one organization's own config. Finding one of these directly under
   `:google` means the config was written flat — that is an error the schema
   reports, not a map of organizations."
  #{:project :topic :oauth :push :health :renew-within-hours})

(def ^:dynamic *tenant*
  "Organization the current thread is acting as. The push door binds it for a
   handler; a comm binds it for a send."
  nil)

(defn organizations?
  "Is this `:google` slice the one shape Isaac reads — a non-empty map of
   organization id to that organization's map?"
  [slice]
  (boolean (and (map? slice)
                (seq slice)
                (not-any? organization-fields (keys slice))
                (every? map? (vals slice)))))

(defn tenants
  "config -> {organization-id organization-config}. Anything that is not the
   declared map of organizations reads as none configured; the schema is what
   says so out loud."
  [config]
  (let [slice (:google config)]
    (if (organizations? slice) slice {})))

(defn ids
  "Every configured organization id, sorted."
  [config]
  (vec (sort (keys (tenants config)))))

(defn tenant-config
  "One organization's complete set, or nil when nobody declared it."
  [config id]
  (get (tenants config) id))

(defn config-path
  "Path into the live config at which `id`'s settings sit."
  [_config id]
  [:google id])

(defn resolve-id
  "Which organization to act as: the one named, else the one bound to this
   thread, else the only one configured. nil when a host with several names
   none, and when none is configured at all."
  ([config] (resolve-id config nil))
  ([config id]
   (or id
       *tenant*
       (let [configured (ids config)]
         (when (= 1 (count configured))
           (first configured))))))

(defn auth-provider
  "Auth-store provider key for an organization's tokens. One key per
   organization, always namespaced — there is no unnamed Google login."
  [id]
  (when id (str "google/" (name id))))

(defn- subscription-project [subscription]
  (second (re-find #"^projects/([^/]+)/" (str subscription))))

(defn tenant-for-subscription
  "Which organization a Pub/Sub push came from, by the project in its
   subscription name. nil when no configured organization owns that project."
  [config subscription]
  (when-let [project (subscription-project subscription)]
    (some (fn [[id tenant]]
            (when (= project (str (:project tenant))) id))
          (sort-by key (tenants config)))))

(defn principal-tenant
  "Which organization a door principal speaks for. Every rule grants
   `:google-pubsub/<organization>`; a bare `:google-pubsub` names none."
  [principal-name]
  (when (and principal-name (namespace principal-name))
    (keyword (name principal-name))))

(defn tenant-of-push
  "Which organization owns this push: `{:tenant id}`, or
   `{:refused :tenant-mismatch ...}` when the subscription it arrived on and
   the service account that signed it belong to different organizations.

   Two independent claims — the project in the subscription name and the
   principal the token proved — must agree. A host that configured no
   `:project` for an organization has only the principal to go on."
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
  "Which organization a comm speaks for: the one its slice names, else the
   only one configured. A comm on a one-organization host names none — that
   is a default *value*, not a second config shape.

   The key is namespaced per comm kind — :gchat/google, :gmail/google — like
   every other key a comm contributes. A bare :google collides in the composed
   comm schema the moment two Google comms are installed on one host, which is
   every host that runs Chat and Gmail together (isaac-1zkz follow-up)."
  [config slice]
  (if-let [named (some slice [:gchat/google :gmail/google :google])]
    (keyword named)
    (let [configured (ids config)]
      (when (= 1 (count configured))
        (first configured)))))

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
