(ns isaac.google.token-spec
  (:require
    [isaac.fs :as fs]
    [isaac.google.oauth :as oauth]
    [isaac.google.token :as sut]
    [isaac.llm.auth.store :as auth-store]
    [isaac.nexus :as nexus]
    [speclj.core :refer [around context describe it should= should-contain should-throw with]]))

(def root "/test/google-token")

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
  )
