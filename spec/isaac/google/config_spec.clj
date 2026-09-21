(ns isaac.google.config-spec
  (:require
    [isaac.google.config :as sut]
    [isaac.schema.lexicon :as lexicon]
    [speclj.core :refer [context describe it should-contain should-not-contain should=]]))

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

  (it "declares push endpoint and service-account"
    (should= :string (get-in sut/google-schema [:value-spec :schema :push :schema :endpoint :type]))
    (should= :string (get-in sut/google-schema [:value-spec :schema :push :schema :service-account :type])))

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
