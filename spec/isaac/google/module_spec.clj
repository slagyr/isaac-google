(ns isaac.google.module-spec
  (:require
    [clojure.edn :as edn]
    [isaac.module.protocol]
    [isaac.google.config :as config]
    [isaac.google.door :as door]
    [isaac.google.module :as sut]
    [isaac.google.people :as people]
    [speclj.core :refer [describe it should should-be-nil should-not-contain should=]]))

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

  (it "declares the google table as organization id -> config, exactly as the namespace defines it (isaac-okfj)"
    (should= config/google-schema (get-in manifest [:isaac.config/schema :google :schema])))

  ;; A trust rule's config refs are static paths that must name an
  ;; organization; the manifest cannot know those ids, so the component
  ;; registers every rule at start (isaac-okfj).
  (it "declares no push-door identity of its own"
    (should-be-nil (:isaac.http/identity manifest)))

  (it "declares the scopes berth and contributes openid and the directory scope"
    (should= :seq (get-in manifest [:berths :isaac.google/scopes :schema :type]))
    (should= ["openid" people/DIRECTORY-SCOPE]
             (:isaac.google/scopes manifest)))

  ;; A Cloud Platform scope on a human's grant puts the whole Google login —
  ;; Gmail, Chat, directory — under the Workspace's Cloud session-control
  ;; clock. Pub/Sub is the service account's job now (isaac-ey6q, isaac-286x).
  (it "contributes no Google Cloud Platform scope to the user's login"
    (doseq [scope (:isaac.google/scopes manifest)]
      (should-not-contain "auth/pubsub" scope)
      (should-not-contain "cloud-platform" scope)))

  ;; Absent, the host starts, validates OK, and only says so an hour later
  ;; when the first heartbeat cannot be published (isaac-286x).
  (it "contributes a config check for the Pub/Sub service account"
    (should= 'isaac.google.service-account/check-credentials
             (get-in manifest [:isaac.config/check :google-pubsub-credentials :fn])))

  (it "declares the registration berth"
    (should= :map (get-in manifest [:berths :isaac.google/registration :schema :type]))
    (should= 'isaac.google.registration/register!
             (get-in manifest [:berths :isaac.google/registration :schema :value-spec :factory])))

  ;; The login ends at this host: Google redirects the operator's browser to
  ;; the callback and isaac.google.http finishes the exchange (isaac-2abl).
  (it "contributes the OAuth callback route beside the push door"
    (let [routes (into {} (map (juxt :path identity)) (:isaac.http/route manifest))]
      (should= :post (get-in routes ["/google/pubsub" :method]))
      (should= :google/push (get-in routes ["/google/pubsub" :scope]))
      (should= :get (get-in routes ["/google/oauth/callback" :method]))
      (should= 'isaac.google.http/oauth-callback (get-in routes ["/google/oauth/callback" :handler]))
      (should= door/CALLBACK-SCOPE (get-in routes ["/google/oauth/callback" :scope]))
      (should= door/CALLBACK-PATH (get-in routes ["/google/oauth/callback" :path]))))

  (it "contributes the registration timer component"
    (should= 'isaac.google.component (get-in manifest [:isaac/component :google-registration :namespace])))

  ;; The smoke probe needs a handler of its own or `--send-live` leaves a
  ;; record behind that the worker warns about forever (isaac-pl8x).
  (it "contributes a no-op handler for its own smoke probe type"
    (should= 'isaac.google.smoke/noop-handler
             (get-in manifest [:isaac.google/handler "isaac.google.smoke/probe"])))
  )
