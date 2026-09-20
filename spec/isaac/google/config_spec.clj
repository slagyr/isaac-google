(ns isaac.google.config-spec
  (:require
    [isaac.google.config :as sut]
    [isaac.schema.lexicon :as lexicon]
    [speclj.core :refer [context describe it should-contain should-not-contain should=]]))

(defn- oauth-field [k]
  (get-in sut/google-schema [:schema :oauth :schema k]))

(def tenant-fields #{:project :topic :oauth :push :renew-within-hours :health})

(describe "isaac.google config schema"

  (it "exposes :google table with :project :topic :oauth :push :renew-within-hours :health"
    (should= tenant-fields (set (keys (:schema sut/google-schema)))))

  (it "requires client-id"
    (should-contain :present? (:validations (oauth-field :client-id))))

  (it "requires client-secret"
    (should-contain :present? (:validations (oauth-field :client-secret))))

  (it "account is optional"
    (should= nil (:validations (oauth-field :account))))

  (it "declares push endpoint and service-account"
    (should= :string (get-in sut/google-schema [:schema :push :schema :endpoint :type]))
    (should= :string (get-in sut/google-schema [:schema :push :schema :service-account :type])))

  (context "a tenant is a complete set (isaac-1zkz)"

    (it "declares the same fields for one tenant as for the flat config"
      (should= tenant-fields (set (keys (:schema sut/tenant-schema)))))

    (it "keys tenants by keyword"
      (should= :keyword (get-in sut/google-schema [:key-spec :type])))

    (it "validates any tenant entry against the tenant schema"
      (should= tenant-fields (set (keys (get-in sut/google-schema [:value-spec :schema])))))

    (it "conforms the flat single-organization config unchanged"
      (should= {:project "marigold"
                :oauth   {:client-id "cid" :client-secret "shh"}}
               (lexicon/conform sut/google-schema
                                {:project "marigold"
                                 :oauth   {:client-id "cid" :client-secret "shh"}})))

    (it "conforms a config of several tenants, keeping every tenant"
      (let [conformed (lexicon/conform sut/google-schema
                                       {:tonotop {:project "marigold"
                                                  :oauth   {:client-id "cid-t" :client-secret "shh"}}
                                        :acme    {:project "acme-prod"
                                                  :oauth   {:client-id "cid-a" :client-secret "shh"}}})]
        (should= "marigold" (get-in conformed [:tonotop :project]))
        (should= "cid-t" (get-in conformed [:tonotop :oauth :client-id]))
        (should= "acme-prod" (get-in conformed [:acme :project]))
        (should= "cid-a" (get-in conformed [:acme :oauth :client-id]))))

    (it "keeps the tenant schema free of a nested tenant map"
      (should-not-contain :value-spec (keys sut/tenant-schema))))
  )
