(ns isaac.google.token-spec
  (:require
    [isaac.fs :as fs]
    [isaac.google.oauth :as oauth]
    [isaac.google.tenants :as tenants]
    [isaac.google.token :as sut]
    [isaac.llm.auth.store :as auth-store]
    [isaac.nexus :as nexus]
    [speclj.core :refer [around context describe it should= should-be-nil should-contain should-throw with]]))

(def root "/test/google-token")

(def flat-config
  {:google {:project "marigold"
            :oauth   {:client-id "cid" :client-secret "shh" :account "yopp@tonotop.com"}}})

(def two-tenant-config
  {:google {:tonotop {:project "marigold"
                      :oauth   {:client-id "cid-t" :client-secret "shh"}}
            :acme    {:project "acme-prod"
                      :oauth   {:client-id "cid-a" :client-secret "shh"}}}})

(describe "isaac.google.token"

  (with mem (fs/mem-fs))

  (around [it]
    (nexus/-with-nested-nexus {:fs @mem :root root}
      (fs/mkdirs @mem root)
      (it)))

  (context "resolve-tokens"

    (it "returns stored tokens when they are still valid"
      (auth-store/save-tokens! root "google"
                               {:access_token "at-live" :refresh_token "rt-1" :expires_in 3600}
                               @mem)
      (should= "at-live" (:access (sut/resolve-tokens {:google {:oauth {:client-id "cid" :client-secret "shh"}}}))))

    (it "refreshes an expired access token without prompting"
      (auth-store/save-tokens! root "google"
                               {:access_token "at-old" :refresh_token "rt-1" :expires_in -1}
                               @mem)
      (with-redefs [oauth/refresh! (fn [_creds refresh]
                                     (should= "rt-1" refresh)
                                     {:access_token "at-2" :expires_in 3600})]
        (let [tokens (sut/resolve-tokens {:google {:oauth {:client-id "cid" :client-secret "shh"}}})]
          (should= "at-2" (:access tokens))
          (should= "rt-1" (:refresh tokens)))))

    (it "explains invalid_grant as a Testing consent-screen problem"
      (auth-store/save-tokens! root "google"
                               {:access_token "at-old" :refresh_token "rt-old" :expires_in -1}
                               @mem)
      (with-redefs [oauth/refresh! (fn [_ _] {:error "invalid_grant"})]
        (let [result (sut/resolve-tokens {:google {:oauth {:client-id "cid" :client-secret "shh"}}})]
          (should= :auth-failed (:error result))
          (should-contain "consent screen" (:message result))
          (should-contain "Testing" (:message result)))))
    )

  (context "token"

    (it "returns the access string"
      (auth-store/save-tokens! root "google"
                               {:access_token "at-live" :refresh_token "rt-1" :expires_in 3600}
                               @mem)
      (should= "at-live" (sut/token)))

    (it "throws when refresh is dead"
      (auth-store/save-tokens! root "google"
                               {:access_token "at-old" :refresh_token "rt-old" :expires_in -1}
                               @mem)
      (with-redefs [oauth/refresh! (fn [_ _] {:error "invalid_grant"})]
        (should-throw clojure.lang.ExceptionInfo (sut/token))))
    )

  (context "one token per tenant (isaac-1zkz)"

    (it "a flat config's login is the tenant's login"
      (auth-store/save-tokens! root "google"
                               {:access_token "at-flat" :refresh_token "rt-1" :expires_in 3600}
                               @mem)
      (should= "at-flat" (:access (sut/resolve-tokens flat-config :default)))
      (should= "at-flat" (sut/token :default)))

    (it "each tenant has its own token"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-tonotop" :refresh_token "rt-t" :expires_in 3600}
                               @mem)
      (auth-store/save-tokens! root "google/acme"
                               {:access_token "at-acme" :refresh_token "rt-a" :expires_in 3600}
                               @mem)
      (should= "at-tonotop" (:access (sut/resolve-tokens two-tenant-config :tonotop)))
      (should= "at-acme" (:access (sut/resolve-tokens two-tenant-config :acme))))

    (it "a tenant with no login of its own does not borrow another's"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-tonotop" :refresh_token "rt-t" :expires_in 3600}
                               @mem)
      (let [result (sut/resolve-tokens two-tenant-config :acme)]
        (should= :auth-failed (:error result))
        (should-contain "acme" (:message result))))

    (it "refreshes with the tenant's own oauth client and stores it back there"
      (auth-store/save-tokens! root "google/acme"
                               {:access_token "at-old" :refresh_token "rt-a" :expires_in -1}
                               @mem)
      (with-redefs [oauth/refresh! (fn [creds refresh]
                                     (should= "cid-a" (:client-id creds))
                                     (should= "rt-a" refresh)
                                     {:access_token "at-new" :expires_in 3600})]
        (should= "at-new" (:access (sut/resolve-tokens two-tenant-config :acme))))
      (should= "at-new" (:access (auth-store/load-tokens root "google/acme" @mem)))
      (should-be-nil (auth-store/load-tokens root "google" @mem)))

    (it "acts as the tenant bound to the thread"
      (auth-store/save-tokens! root "google/acme"
                               {:access_token "at-acme" :refresh_token "rt-a" :expires_in 3600}
                               @mem)
      (binding [tenants/*tenant* :acme]
        (should= "at-acme" (:access (sut/resolve-tokens two-tenant-config)))))
    )
  )
