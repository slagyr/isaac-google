(ns isaac.google.tenants-spec
  (:require
    [isaac.google.tenants :as sut]
    [speclj.core :refer [context describe it should= should-be-nil should-not should]]))

(def flat-config
  {:google {:project "marigold"
            :topic   "projects/marigold/topics/isaac"
            :oauth   {:client-id "cid" :account "yopp@tonotop.com"}
            :push    {:endpoint        "https://isaac.example/google/pubsub"
                      :service-account "push@marigold.iam.gserviceaccount.com"}}})

(def one-tenant-config
  {:google {:tonotop {:project "marigold"
                      :topic   "projects/marigold/topics/isaac"
                      :oauth   {:client-id "cid-t" :account "yopp@tonotop.com"}
                      :push    {:endpoint        "https://isaac.example/google/pubsub"
                                :service-account "push@marigold.iam.gserviceaccount.com"}}}})

(def two-tenant-config
  (assoc-in one-tenant-config [:google :acme]
            {:project "acme-prod"
             :topic   "projects/acme-prod/topics/isaac"
             :oauth   {:client-id "cid-a" :account "isaac@acme.com"}
             :push    {:endpoint        "https://isaac.example/google/pubsub"
                       :service-account "push@acme-prod.iam.gserviceaccount.com"}}))

(def comm-config
  {:comms {:gchat      {:gchat/google :tonotop :gchat/account "yopp@tonotop.com"}
           :gchat-acme {:type :gchat :gchat/google :acme}
           :gmail      {:gmail/account "yopp@tonotop.com"}
           :discord    {:type :discord}}})

(def tenanted-comms (merge two-tenant-config comm-config))

(describe "isaac.google.tenants"

  (context "one shape: a map of organization id to config (isaac-okfj)"

    (it "reads the declared organizations"
      (should= (:google two-tenant-config) (sut/tenants two-tenant-config))
      (should= [:acme :tonotop] (sut/ids two-tenant-config)))

    (it "reads one organization the same way it reads several"
      (should= (:google one-tenant-config) (sut/tenants one-tenant-config))
      (should= [:tonotop] (sut/ids one-tenant-config)))

    ;; The flat map isaac-1zkz accepted is not a second shape: :oauth and :push
    ;; are maps, so reinterpreting would silently invent organizations named
    ;; :oauth and :push. The schema reports it instead.
    (it "reads a flat config as no organizations at all"
      (should= {} (sut/tenants flat-config))
      (should= [] (sut/ids flat-config))
      (should= {} (sut/tenants {:google {:oauth {:client-id "cid"}}}))
      (should-not (sut/organizations? (:google flat-config))))

    (it "knows the one shape when it sees it"
      (should (sut/organizations? (:google two-tenant-config)))
      (should (sut/organizations? (:google one-tenant-config))))

    (it "has no organizations when google is unconfigured or not a map"
      (should= {} (sut/tenants {}))
      (should= {} (sut/tenants {:google {}}))
      (should= {} (sut/tenants {:google "nonsense"}))
      (should= [] (sut/ids {})))

    (it "hands back one organization's complete set"
      (should= "acme-prod" (:project (sut/tenant-config two-tenant-config :acme)))
      (should= "cid-a" (get-in (sut/tenant-config two-tenant-config :acme) [:oauth :client-id])))

    (it "has no config for an organization nobody declared"
      (should-be-nil (sut/tenant-config two-tenant-config :nobody)))

    (it "always names the organization in the config path"
      (should= [:google :acme] (sut/config-path two-tenant-config :acme))
      (should= [:google :tonotop] (sut/config-path one-tenant-config :tonotop))))

  (context "which organization am I acting as"

    (it "an explicit organization wins"
      (should= :acme (sut/resolve-id two-tenant-config :acme)))

    (it "the dynamic binding is next"
      (binding [sut/*tenant* :acme]
        (should= :acme (sut/resolve-id two-tenant-config nil))))

    (it "a lone organization needs no naming"
      (should= :tonotop (sut/resolve-id one-tenant-config nil)))

    (it "names no organization when several are configured and none is named"
      (should-be-nil (sut/resolve-id two-tenant-config nil)))

    (it "names no organization when none is configured"
      (should-be-nil (sut/resolve-id {} nil))
      (should-be-nil (sut/resolve-id flat-config nil))))

  (context "tokens are stored per organization"

    (it "stores every organization under google/<id>"
      (should= "google/acme" (sut/auth-provider :acme))
      (should= "google/tonotop" (sut/auth-provider :tonotop)))

    (it "has no provider key for no organization"
      (should-be-nil (sut/auth-provider nil))))

  (context "which organization a push came from"

    (it "reads the organization off the subscription's project"
      (should= :acme (sut/tenant-for-subscription two-tenant-config
                                                  "projects/acme-prod/subscriptions/isaac"))
      (should= :tonotop (sut/tenant-for-subscription two-tenant-config
                                                     "projects/marigold/subscriptions/isaac")))

    (it "knows no organization for a project nobody configured"
      (should-be-nil (sut/tenant-for-subscription two-tenant-config
                                                  "projects/impostor/subscriptions/isaac"))
      (should-be-nil (sut/tenant-for-subscription two-tenant-config nil))))

  (context "which organization a door principal speaks for"

    (it "reads :google-pubsub/acme as acme"
      (should= :acme (sut/principal-tenant :google-pubsub/acme)))

    (it "knows nothing from a principal that names no organization"
      (should-be-nil (sut/principal-tenant :google-pubsub))
      (should-be-nil (sut/principal-tenant nil))))

  (context "the organization a push belongs to"

    (it "takes the organization from the subscription's project"
      (should= {:tenant :acme}
               (sut/tenant-of-push two-tenant-config
                                   {:subscription "projects/acme-prod/subscriptions/isaac"}
                                   :google-pubsub/acme)))

    ;; Acme's service account signed the token, but the push arrived on
    ;; tonotop's subscription. One of the two is lying; keep nothing.
    (it "refuses a push whose subscription and service account disagree"
      (should= {:refused          :tenant-mismatch
                :tenant           :tonotop
                :principal-tenant :acme}
               (sut/tenant-of-push two-tenant-config
                                   {:subscription "projects/marigold/subscriptions/isaac"}
                                   :google-pubsub/acme)))

    ;; A host that never configured :project still has a working door.
    (it "falls back to the principal's organization when no configured project owns the subscription"
      (should= {:tenant :acme}
               (sut/tenant-of-push two-tenant-config
                                   {:subscription "projects/impostor/subscriptions/isaac"}
                                   :google-pubsub/acme)))

    (it "reads a push with no subscription as the principal's organization"
      (should= {:tenant :tonotop}
               (sut/tenant-of-push one-tenant-config {} :google-pubsub/tonotop)))

    (it "reads an unauthenticated push as the only organization configured"
      (should= {:tenant :tonotop}
               (sut/tenant-of-push one-tenant-config {} nil)))

    (it "names no organization for an unauthenticated push on a host serving several"
      (should= {:tenant nil}
               (sut/tenant-of-push two-tenant-config {} nil))))

  (context "a comm speaks for one organization"

    (it "finds the comms of one kind, by :type or by the conventional name"
      (should= [:gchat :gchat-acme] (mapv key (sut/comms comm-config :gchat)))
      (should= [:gmail] (mapv key (sut/comms comm-config :gmail)))
      (should= [] (sut/comms comm-config :imessage)))

    (it "a comm speaks for the organization it names"
      (should= :acme (sut/of-comm two-tenant-config {:gchat/google "acme"}))
      (should= :tonotop (sut/of-comm two-tenant-config {:gmail/google "tonotop"})))

    (it "still reads a bare :google, for configs written before the rename"
      (should= :acme (sut/of-comm two-tenant-config {:google "acme"})))

    (it "a comm naming none speaks for the only organization configured"
      (should= :tonotop (sut/of-comm one-tenant-config {})))

    (it "a comm naming none names no organization where several are configured"
      (should-be-nil (sut/of-comm two-tenant-config {})))

    (it "only the comms of one organization"
      (should= [:gchat-acme] (mapv key (sut/comms-for tenanted-comms :gchat :acme)))
      (should= [:gchat] (mapv key (sut/comms-for tenanted-comms :gchat :tonotop)))
      (should= [] (sut/comms-for tenanted-comms :gmail :acme)))))
