(ns isaac.google.token-spec
  (:require
    [isaac.fs :as fs]
    [isaac.google.oauth :as oauth]
    [isaac.google.tenants :as tenants]
    [isaac.google.token :as sut]
    [isaac.llm.auth.store :as auth-store]
    [isaac.nexus :as nexus]
    [speclj.core :refer [around context describe it should= should-be-nil should-contain should-not-contain should-throw with]]))

(def root "/test/google-token")

(def one-tenant-config
  {:google {:tonotop {:project "marigold"
                      :oauth   {:client-id "cid" :client-secret "shh" :account "yopp@tonotop.com"}}}})

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
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-live" :refresh_token "rt-1" :expires_in 3600}
                               @mem)
      (should= "at-live" (:access (sut/resolve-tokens one-tenant-config))))

    (it "refreshes an expired access token without prompting"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-old" :refresh_token "rt-1" :expires_in -1}
                               @mem)
      (with-redefs [oauth/refresh! (fn [_creds refresh]
                                     (should= "rt-1" refresh)
                                     {:access_token "at-2" :expires_in 3600})]
        (let [tokens (sut/resolve-tokens one-tenant-config)]
          (should= "at-2" (:access tokens))
          (should= "rt-1" (:refresh tokens)))))

    ;; The rejection is all Google told us. Naming a cause nobody detected —
    ;; a Testing consent screen an Internal app cannot even have — sent the
    ;; operator to a console page with nothing to change (isaac-ey6q).
    (it "reports invalid_grant without blaming a cause it never checked"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-old" :refresh_token "rt-old" :expires_in -1}
                               @mem)
      (with-redefs [oauth/refresh! (fn [_ _] {:error "invalid_grant"})]
        (let [result (sut/resolve-tokens one-tenant-config)]
          (should= :auth-failed (:error result))
          (should-contain "invalid_grant" (:message result))
          (should-contain "isaac google login" (:message result))
          (should-not-contain "Testing" (:message result)))))

    ;; Google may answer a refresh with a *new* refresh token; the one that
    ;; bought it dies on the spot. A rotation that never reaches disk leaves a
    ;; dead token there and invalid_grant hours later (isaac-ey6q).
    (it "stores a refresh token the refresh response rotated"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-old" :refresh_token "rt-1" :expires_in -1}
                               @mem)
      (with-redefs [oauth/refresh! (fn [_creds _refresh]
                                     {:access_token "at-2" :refresh_token "rt-2" :expires_in 3600})]
        (should= "rt-2" (:refresh (sut/resolve-tokens one-tenant-config))))
      (should= "rt-2" (:refresh (auth-store/load-tokens root "google/tonotop" @mem))))

    ;; The scheduler and a live turn can find the same access token stale at
    ;; once. Two refreshes spend the same refresh token twice, and the loser
    ;; writes its older answer over the winner's — a dead token on disk
    ;; (isaac-ey6q).
    (it "refreshes once when two threads find the token stale"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-old" :refresh_token "rt-1" :expires_in -1}
                               @mem)
      (let [spent* (atom [])]
        (with-redefs [oauth/refresh! (fn [_creds refresh]
                                       (let [n (count (swap! spent* conj refresh))]
                                         (Thread/sleep 100)
                                         {:access_token  (str "at-" (inc n))
                                          :refresh_token (str "rt-" (inc n))
                                          :expires_in    3600}))]
          (let [turns (mapv (fn [_] (future (sut/resolve-tokens one-tenant-config)))
                            (range 2))]
            (doseq [turn turns] (deref turn 5000 nil))))
        (should= ["rt-1"] @spent*)
        (should= "rt-2" (:refresh (auth-store/load-tokens root "google/tonotop" @mem)))))

    ;; With no flat form and no default organization, a config that names none
    ;; has no login to resolve — and the message says what to configure
    ;; (isaac-okfj).
    (it "asks for an organization when the config names none"
      (let [result (sut/resolve-tokens {:google {:project "marigold"}})]
        (should= :auth-failed (:error result))
        (should-contain "google.<organization>" (:message result))))
    )

  (context "token"

    (it "returns the access string"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-live" :refresh_token "rt-1" :expires_in 3600}
                               @mem)
      (should= "at-live" (sut/token :tonotop)))

    (it "throws when refresh is dead"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-old" :refresh_token "rt-old" :expires_in -1}
                               @mem)
      (with-redefs [oauth/refresh! (fn [_ _] {:error "invalid_grant"})]
        (should-throw clojure.lang.ExceptionInfo (sut/token :tonotop))))
    )

  (context "one token per organization (isaac-1zkz, isaac-okfj)"

    (it "a lone organization's login needs no naming"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-tonotop" :refresh_token "rt-1" :expires_in 3600}
                               @mem)
      (should= "at-tonotop" (:access (sut/resolve-tokens one-tenant-config)))
      (should= "at-tonotop" (:access (sut/resolve-tokens one-tenant-config :tonotop))))

    (it "each organization has its own token"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-tonotop" :refresh_token "rt-t" :expires_in 3600}
                               @mem)
      (auth-store/save-tokens! root "google/acme"
                               {:access_token "at-acme" :refresh_token "rt-a" :expires_in 3600}
                               @mem)
      (should= "at-tonotop" (:access (sut/resolve-tokens two-tenant-config :tonotop)))
      (should= "at-acme" (:access (sut/resolve-tokens two-tenant-config :acme))))

    (it "an organization with no login of its own does not borrow another's"
      (auth-store/save-tokens! root "google/tonotop"
                               {:access_token "at-tonotop" :refresh_token "rt-t" :expires_in 3600}
                               @mem)
      (let [result (sut/resolve-tokens two-tenant-config :acme)]
        (should= :auth-failed (:error result))
        (should-contain "acme" (:message result))))

    (it "refreshes with the organization's own oauth client and stores it back there"
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

    (it "acts as the organization bound to the thread"
      (auth-store/save-tokens! root "google/acme"
                               {:access_token "at-acme" :refresh_token "rt-a" :expires_in 3600}
                               @mem)
      (binding [tenants/*tenant* :acme]
        (should= "at-acme" (:access (sut/resolve-tokens two-tenant-config)))))
    )
  )
