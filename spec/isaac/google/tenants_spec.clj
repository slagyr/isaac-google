(ns isaac.google.tenants-spec
  (:require
    [isaac.google.tenants :as sut]
    [speclj.core :refer [context describe it should= should-be-nil]]))

(def flat-config
  {:google {:project "marigold"
            :topic   "projects/marigold/topics/isaac"
            :oauth   {:client-id "cid" :account "yopp@tonotop.com"}
            :push    {:endpoint        "https://isaac.example/google/pubsub"
                      :service-account "push@marigold.iam.gserviceaccount.com"}}})

(def two-tenant-config
  {:google {:tonotop {:project "marigold"
                      :topic   "projects/marigold/topics/isaac"
                      :oauth   {:client-id "cid-t" :account "yopp@tonotop.com"}
                      :push    {:endpoint        "https://isaac.example/google/pubsub"
                                :service-account "push@marigold.iam.gserviceaccount.com"}}
            :acme    {:project "acme-prod"
                      :topic   "projects/acme-prod/topics/isaac"
                      :oauth   {:client-id "cid-a" :account "isaac@acme.com"}
                      :push    {:endpoint        "https://isaac.example/google/pubsub"
                                :service-account "push@acme-prod.iam.gserviceaccount.com"}}}})

(def comm-config
  {:comms {:gchat      {:google :tonotop :gchat/account "yopp@tonotop.com"}
           :gchat-acme {:type :gchat :google :acme}
           :gmail      {:gmail/account "yopp@tonotop.com"}
           :discord    {:type :discord}}})

(def tenanted-comms (merge two-tenant-config comm-config))

(describe "isaac.google.tenants"

  (context "a flat config is the :default tenant"

    (it "reads the flat map as one tenant"
      (should= {:default (:google flat-config)} (sut/tenants flat-config)))

    (it "lists :default"
      (should= [:default] (sut/ids flat-config)))

    (it "hands back the flat map for :default"
      (should= (:google flat-config) (sut/tenant-config flat-config :default)))

    (it "keeps the flat config path so existing config refs resolve"
      (should= [:google] (sut/config-path flat-config :default)))

    (it "has no tenants when google is unconfigured"
      (should= {} (sut/tenants {}))
      (should= [] (sut/ids {})))

    (it "keeps the flat path when nothing is configured at all, so error messages name google.oauth.client-id"
      (should= [:google] (sut/config-path {} :default))
      (should= [:google] (sut/config-path {:google {}} :default))))

  (context "several tenants"

    (it "lists every tenant"
      (should= [:acme :tonotop] (sut/ids two-tenant-config)))

    (it "hands back one tenant's complete set"
      (should= "acme-prod" (:project (sut/tenant-config two-tenant-config :acme)))
      (should= "cid-a" (get-in (sut/tenant-config two-tenant-config :acme) [:oauth :client-id])))

    (it "names the tenant in the config path"
      (should= [:google :acme] (sut/config-path two-tenant-config :acme)))

    (it "has no config for a tenant nobody declared"
      (should-be-nil (sut/tenant-config two-tenant-config :nobody))))

  (context "which tenant am I acting as"

    (it "an explicit tenant wins"
      (should= :acme (sut/resolve-id two-tenant-config :acme)))

    (it "the dynamic binding is next"
      (binding [sut/*tenant* :acme]
        (should= :acme (sut/resolve-id two-tenant-config nil))))

    (it "a lone tenant needs no naming"
      (should= :default (sut/resolve-id flat-config nil))
      (should= :tonotop (sut/resolve-id (update two-tenant-config :google dissoc :acme) nil)))

    (it "falls back to :default when several are configured and none is named"
      (should= :default (sut/resolve-id two-tenant-config nil))))

  (context "tokens are stored per tenant"

    (it "keeps the plain google provider for the default tenant"
      (should= "google" (sut/auth-provider :default))
      (should= "google" (sut/auth-provider nil)))

    (it "stores a named tenant under google/<tenant>"
      (should= "google/acme" (sut/auth-provider :acme))))

  (context "which tenant a push came from"

    (it "reads the tenant off the subscription's project"
      (should= :acme (sut/tenant-for-subscription two-tenant-config
                                                  "projects/acme-prod/subscriptions/isaac"))
      (should= :tonotop (sut/tenant-for-subscription two-tenant-config
                                                     "projects/marigold/subscriptions/isaac")))

    (it "reads a flat config's subscription as :default"
      (should= :default (sut/tenant-for-subscription flat-config
                                                     "projects/marigold/subscriptions/isaac")))

    (it "knows no tenant for a project nobody configured"
      (should-be-nil (sut/tenant-for-subscription two-tenant-config
                                                  "projects/impostor/subscriptions/isaac"))
      (should-be-nil (sut/tenant-for-subscription two-tenant-config nil))))

  (context "which tenant a door principal speaks for"

    (it "reads a plain :google-pubsub as the default tenant"
      (should= :default (sut/principal-tenant :google-pubsub)))

    (it "reads :google-pubsub/acme as acme"
      (should= :acme (sut/principal-tenant :google-pubsub/acme)))

    (it "knows nothing from no principal"
      (should-be-nil (sut/principal-tenant nil))))

  (context "the tenant a push belongs to"

    (it "takes the tenant from the subscription's project"
      (should= {:tenant :acme}
               (sut/tenant-of-push two-tenant-config
                                   {:subscription "projects/acme-prod/subscriptions/isaac"}
                                   :google-pubsub/acme)))

    ;; Acme's service account signed the token, but the push arrived on
    ;; tonotop's subscription. One of the two is lying; keep nothing.
    (it "refuses a push whose subscription and service account disagree"
      (should= {:refused         :tenant-mismatch
                :tenant          :tonotop
                :principal-tenant :acme}
               (sut/tenant-of-push two-tenant-config
                                   {:subscription "projects/marigold/subscriptions/isaac"}
                                   :google-pubsub/acme)))

    ;; A host that never configured :project still has a working door.
    (it "falls back to the principal's tenant when no configured project owns the subscription"
      (should= {:tenant :acme}
               (sut/tenant-of-push two-tenant-config
                                   {:subscription "projects/impostor/subscriptions/isaac"}
                                   :google-pubsub/acme)))

    (it "reads a push with no subscription as the principal's tenant"
      (should= {:tenant :default}
               (sut/tenant-of-push flat-config {} :google-pubsub)))

    (it "reads an unauthenticated push as the default tenant"
      (should= {:tenant :default}
               (sut/tenant-of-push flat-config {} nil))))

  (context "a comm speaks for one organization"

    (it "finds the comms of one kind, by :type or by the conventional name"
      (should= [:gchat :gchat-acme] (mapv key (sut/comms comm-config :gchat)))
      (should= [:gmail] (mapv key (sut/comms comm-config :gmail)))
      (should= [] (sut/comms comm-config :imessage)))

    (it "a comm speaks for the organization it names"
      (should= :tonotop (sut/of-comm two-tenant-config {:google :tonotop}))
      (should= :acme (sut/of-comm two-tenant-config {:google "acme"})))

    (it "a comm naming none speaks for the only organization configured"
      (should= :default (sut/of-comm flat-config {}))
      (should= :acme (sut/of-comm {:google {:acme {:project "acme-prod"}}} {})))

    (it "a comm naming none where several are configured speaks for the default"
      (should= :default (sut/of-comm two-tenant-config {})))

    (it "only the comms of one organization"
      (should= [:gchat-acme] (mapv key (sut/comms-for tenanted-comms :gchat :acme)))
      (should= [:gchat] (mapv key (sut/comms-for tenanted-comms :gchat :tonotop)))
      (should= [] (sut/comms-for tenanted-comms :gmail :acme))))
  )
