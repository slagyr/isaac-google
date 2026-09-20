(ns isaac.google.module-spec
  (:require
    [clojure.edn :as edn]
    [isaac.module.protocol]
    [isaac.google.module :as sut]
    [isaac.google.people :as people]
    [speclj.core :refer [describe it should should=]]))

(def manifest
  (edn/read-string (slurp "resources/isaac-manifest.edn")))

(describe "isaac.google.module"

  (it "returns a module"
    (should (satisfies? isaac.module.protocol/Module (sut/create-module))))

  (it "declares its module id"
    (should= :isaac.google (:id manifest)))

  (it "contributes the google CLI command"
    (should= 'isaac.google.cli (get-in manifest [:isaac/cli :google :namespace])))

  (it "declares the google config table"
    (should= :map (get-in manifest [:isaac.config/schema :google :schema :type])))

  (it "declares the scopes berth and contributes openid plus the directory scope"
    (should= :seq (get-in manifest [:berths :isaac.google/scopes :schema :type]))
    (should= ["openid" people/DIRECTORY-SCOPE] (:isaac.google/scopes manifest)))

  (it "declares the registration berth"
    (should= :map (get-in manifest [:berths :isaac.google/registration :schema :type]))
    (should= 'isaac.google.registration/register!
             (get-in manifest [:berths :isaac.google/registration :schema :value-spec :factory])))

  (it "contributes the registration timer component"
    (should= 'isaac.google.component (get-in manifest [:isaac/component :google-registration :namespace])))
  )
