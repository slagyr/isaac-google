(ns isaac.google.config-spec
  (:require
    [isaac.google.config :as sut]
    [isaac.schema.lexicon :as lexicon]
    [speclj.core :refer [context describe it should-be-nil should-contain should-not-contain should=]]))

(defn- oauth-field [k]
  (get-in sut/google-schema [:value-spec :schema :oauth :schema k]))

(def tenant-fields #{:project :topic :oauth :push :renew-within-hours :health})

(describe "isaac.google config schema"

  (it "exposes one organization's table with :project :topic :oauth :push :renew-within-hours :health"
    (should= tenant-fields (set (keys (get-in sut/google-schema [:value-spec :schema])))))

  (it "requires client-id"
    (should-contain :present? (:validations (oauth-field :client-id))))

  (it "requires client-secret"
    (should-contain :present? (:validations (oauth-field :client-secret))))

  (it "account is optional"
    (should= nil (:validations (oauth-field :account))))

  ;; Most hosts already publish a push endpoint, and the callback hangs off
  ;; the same origin; redirect-base is for the host whose OAuth callback and
  ;; Pub/Sub door are not the same public name (isaac-2abl).
  (it "redirect-base is an optional string, documented as the login's public base"
    (should= :string (:type (oauth-field :redirect-base)))
    (should= nil (:validations (oauth-field :redirect-base)))
    (should-contain "/google/oauth/callback" (:description (oauth-field :redirect-base))))

  ;; Health is per organization: how long it may be quiet, and the heartbeat
  ;; that proves the push pipeline when nobody is talking (isaac-an14).
  (it "declares the health thresholds an organization sets"
    (should= #{:silent-after-hours :heartbeat}
             (set (keys (get-in sut/google-schema [:value-spec :schema :health :schema])))))

  ;; Naming the interval is what turns the watch on, and it is the only thing
  ;; that can say what late means — so there is no separate switch to leave on
  ;; over a heartbeat nothing defines (isaac-clly).
  (it "declares the expected interval and its grace, and nothing else"
    (should= #{:expected-interval-ms :grace-ms :enabled}
             (set (keys (get-in sut/google-schema [:value-spec :schema :health :schema :heartbeat :schema]))))
    (should= :int (get-in sut/google-schema [:value-spec :schema :health :schema :heartbeat :schema :expected-interval-ms :type]))
    (should= :int (get-in sut/google-schema [:value-spec :schema :health :schema :heartbeat :schema :grace-ms :type])))

  ;; An operator who had it on is told what replaced it, rather than having
  ;; the key read as an unknown-key warning and the watch stay dark.
  ;; Retired, but not a refusal: a leftover switch changes nothing now that
  ;; the interval is what turns the watch on, and a host that merely receives
  ;; should not be stopped from starting over it (isaac-clly).
  (it "retires the enabled switch without making it a hard error"
    (let [enabled (get-in sut/google-schema [:value-spec :schema :health :schema :heartbeat :schema :enabled])]
      (should-be-nil (:validations enabled))
      (should-contain "Retired" (:description enabled))
      (should-contain "expected-interval-ms" (:description enabled))))

  (it "declares no deadline — there is no send to be late from"
    (should-be-nil (get-in sut/google-schema [:value-spec :schema :health :schema :heartbeat :schema :deadline-ms])))

  (it "declares push endpoint and service-account"
    (should= :string (get-in sut/google-schema [:value-spec :schema :push :schema :endpoint :type]))
    (should= :string (get-in sut/google-schema [:value-spec :schema :push :schema :service-account :type])))

  ;; Isaac publishes nothing, so it names no publishing identity. Cloud
  ;; Scheduler publishes the heartbeat as a Google APIs service account inside
  ;; GCP, and there is no key anywhere to configure (isaac-clly).
  (it "declares no Pub/Sub publishing credential at all"
    (should-be-nil (get-in sut/google-schema [:value-spec :schema :pubsub])))

  (context "one shape: organization id -> that organization's config (isaac-okfj)"

    (it "declares an organization's own fields"
      (should= tenant-fields (set (keys (:schema sut/tenant-schema)))))

    (it "keys organizations by keyword"
      (should= :keyword (get-in sut/google-schema [:key-spec :type])))

    (it "validates every entry against the organization schema"
      (should= tenant-fields (set (keys (get-in sut/google-schema [:value-spec :schema])))))

    ;; With no flat form there is nothing to mis-apply: :google declares no
    ;; fields of its own, so a nested config reports no unknown keys
    ;; (isaac-pvfq).
    (it "declares no fields of its own under :google"
      (should-not-contain :schema (keys sut/google-schema)))

    (it "names the shape it wants when an entry is not an organization map"
      (should-contain "map of organization id to config" (:message sut/tenant-schema)))

    (it "conforms a config of several organizations, keeping every one"
      (let [conformed (lexicon/conform sut/google-schema
                                       {:tonotop {:project "marigold"
                                                  :oauth   {:client-id "cid-t" :client-secret "shh"}}
                                        :acme    {:project "acme-prod"
                                                  :oauth   {:client-id "cid-a" :client-secret "shh"}}})]
        (should= "marigold" (get-in conformed [:tonotop :project]))
        (should= "cid-t" (get-in conformed [:tonotop :oauth :client-id]))
        (should= "acme-prod" (get-in conformed [:acme :project]))
        (should= "cid-a" (get-in conformed [:acme :oauth :client-id]))))

    (it "keeps one organization's schema free of a nested organization map"
      (should-not-contain :value-spec (keys sut/tenant-schema))))
  )
