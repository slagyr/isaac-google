(ns isaac.google.config-spec
  (:require
    [isaac.google.config :as sut]
    [speclj.core :refer [describe it should-contain should=]]))

(defn- oauth-field [k]
  (get-in sut/google-schema [:schema :oauth :schema k]))

(describe "isaac.google config schema"

  (it "exposes :google table with :project :topic :oauth"
    (should= #{:project :topic :oauth} (set (keys (:schema sut/google-schema)))))

  (it "requires client-id"
    (should-contain :present? (:validations (oauth-field :client-id))))

  (it "requires client-secret"
    (should-contain :present? (:validations (oauth-field :client-secret))))

  (it "account is optional"
    (should= nil (:validations (oauth-field :account))))
  )
