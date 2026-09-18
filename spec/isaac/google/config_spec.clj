(ns isaac.google.config-spec
  (:require
    [isaac.google.config :as sut]
    [speclj.core :refer [describe it should-contain should=]]))

(defn- oauth-field [k]
  (get-in sut/google-schema [:schema :oauth :schema k]))

(describe "isaac.google config schema"

  (it "exposes :google table with :project :topic :oauth :push :renew-within-hours"
    (should= #{:project :topic :oauth :push :renew-within-hours} (set (keys (:schema sut/google-schema)))))

  (it "requires client-id"
    (should-contain :present? (:validations (oauth-field :client-id))))

  (it "requires client-secret"
    (should-contain :present? (:validations (oauth-field :client-secret))))

  (it "account is optional"
    (should= nil (:validations (oauth-field :account))))

  (it "declares push endpoint and service-account"
    (should= :string (get-in sut/google-schema [:schema :push :schema :endpoint :type]))
    (should= :string (get-in sut/google-schema [:schema :push :schema :service-account :type])))
  )
