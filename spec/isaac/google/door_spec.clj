(ns isaac.google.door-spec
  (:require
    [isaac.google.door :as sut]
    [speclj.core :refer :all]))

(def one-tenant-config
  {:google {:tonotop {:project "marigold"
                      :push    {:endpoint        "https://isaac.example/google/pubsub"
                                :service-account "push@marigold.iam.gserviceaccount.com"}}}})

(def two-tenant-config
  {:google {:tonotop {:project "marigold"
                      :push    {:endpoint        "https://isaac.example/google/pubsub"
                                :service-account "push@marigold.iam.gserviceaccount.com"}}
            :acme    {:project "acme-prod"
                      :push    {:endpoint        "https://isaac.example/google/pubsub"
                                :service-account "push@acme-prod.iam.gserviceaccount.com"}}}})

(describe "the push door's trust rules"

  (context "one rule per organization (isaac-1zkz, isaac-okfj)"

    (it "names a one-organization host's rule after that organization too"
      (should= [:google-pubsub/tonotop] (keys (sut/trust-rules one-tenant-config))))

    (it "names each organization's rule after the organization"
      (should= [:google-pubsub/acme :google-pubsub/tonotop]
               (sort (keys (sut/trust-rules two-tenant-config)))))

    ;; The rule's audience and service account are config refs, so a rule is
    ;; inert until that organization's push is configured.
    (it "points each rule at its own tenant's endpoint and service account"
      (let [rule (get (sut/trust-rules two-tenant-config) :google-pubsub/acme)]
        (should= [:google :acme :push :endpoint] (:audience rule))
        (should= [:google :acme :push :service-account] (get-in rule [:claims :email]))
        (should= true (get-in rule [:claims :email_verified]))))

    (it "names the organization in a one-organization host's refs as well"
      (let [rule (get (sut/trust-rules one-tenant-config) :google-pubsub/tonotop)]
        (should= [:google :tonotop :push :endpoint] (:audience rule))
        (should= [:google :tonotop :push :service-account] (get-in rule [:claims :email]))))

    (it "has no rule for a flat config, which is no organizations at all"
      (should= {} (sut/trust-rules {:google {:project "marigold"}})))

    (it "trusts only tokens Google itself signed"
      (let [rule (get (sut/trust-rules one-tenant-config) :google-pubsub/tonotop)]
        (should= "https://accounts.google.com" (:issuer rule))
        (should= "https://www.googleapis.com/oauth2/v3/certs" (:jwks rule))))

    (it "grants each organization's principal nothing but the push scope"
      (let [rule (get (sut/trust-rules two-tenant-config) :google-pubsub/tonotop)]
        (should= :google-pubsub/tonotop (get-in rule [:principal :name]))
        (should= #{:google/push} (get-in rule [:principal :scopes]))))

    (it "has no rules for a host with no Google at all"
      (should= {} (sut/trust-rules {}))))

  (context "registering them"

    (it "registers every tenant's rule with the http identity seam"
      (let [registered (atom [])]
        (sut/register-trust-rules! two-tenant-config (fn [entry] (swap! registered conj entry)))
        (should= [:google-pubsub/acme :google-pubsub/tonotop]
                 (sort (map first @registered)))))

    ;; isaac-http is not a runtime dependency of this module; a host without
    ;; it simply has no door to register with.
    (it "is quiet when there is no http module to register with"
      (should-be-nil (sut/register-trust-rules! two-tenant-config nil))))

  (context "where Google sends the operator back (isaac-2abl)"

    ;; The callback is opt-in. A push endpoint says nothing about the OAuth
    ;; client: a Desktop client cannot carry a redirect URI, so a host that
    ;; publishes a door still pastes a code unless it states a redirect base.
    (it "publishing a push endpoint alone keeps the paste-a-code login"
      (should-be-nil (sut/redirect-uri one-tenant-config :tonotop)))

    (it "a stated redirect base selects the callback, trailing slash and all"
      (let [config (assoc-in one-tenant-config [:google :tonotop :oauth :redirect-base]
                             "https://isaac.tonotop.example/")]
        (should= "https://isaac.tonotop.example/google/oauth/callback"
                 (sut/redirect-uri config :tonotop))))

    (it "has nowhere to send the operator without a base"
      (should-be-nil (sut/redirect-uri {:google {:tonotop {:oauth {:client-id "cid"}}}} :tonotop))
      (should-be-nil (sut/redirect-uri {} nil))))

  (context "the callback's own door"

    (it "opens itself for the consent redirect, which carries no credentials"
      (should= {:name :google/oauth-callback :scopes #{:google/oauth-callback}}
               (sut/callback-verifier {:request-method :get :uri "/google/oauth/callback"})))

    (it "opens nothing else"
      (should-be-nil (sut/callback-verifier {:request-method :get :uri "/google/pubsub"}))
      (should-be-nil (sut/callback-verifier {:request-method :post :uri "/google/oauth/callback"})))

    (it "registers itself with the http identity seam"
      (let [registered (atom [])]
        (sut/register-callback! one-tenant-config (fn [entry] (swap! registered conj entry)))
        (should= [sut/callback-verifier] @registered)))

    ;; Registering any verifier turns isaac-http's auth on for the whole
    ;; server. A host with no Google organization has no login to complete,
    ;; so it gets no verifier and no surprise.
    (it "stays out of a host that serves no Google organization"
      (let [registered (atom [])]
        (sut/register-callback! {} (fn [entry] (swap! registered conj entry)))
        (should= [] @registered)))

    (it "is quiet when there is no http module to register with"
      (should-be-nil (sut/register-callback! one-tenant-config nil))))
  )
