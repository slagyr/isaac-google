(ns isaac.google.oauth-spec
  (:require
    [clojure.string :as str]
    [isaac.google.oauth :as sut]
    [isaac.google.scopes :as scopes]
    [isaac.llm.http :as llm-http]
    [speclj.core :refer [context describe it should should-contain should= should-not]]))

(describe "isaac.google.oauth"

  (context "authorization-url"

    (it "points at Google's auth endpoint with the union of scopes, redirect, and state"
      (let [url (sut/authorization-url {:client-id    "isaac-test.apps.googleusercontent.com"
                                        :scopes       ["openid" "https://www.googleapis.com/auth/chat.messages.readonly"]
                                        :redirect-uri "http://localhost:4242/"
                                        :state        "st-marigold"})]
        (should (str/starts-with? url "https://accounts.google.com/o/oauth2/v2/auth?"))
        (should-contain "client_id=isaac-test.apps.googleusercontent.com" url)
        (should-contain "redirect_uri=http%3A%2F%2Flocalhost%3A4242%2F" url)
        (should-contain "response_type=code" url)
        (should-contain "state=st-marigold" url)
        (should-contain "access_type=offline" url)
        (should-contain "openid" url)
        (should-contain "chat.messages.readonly" url)))
    )

  (context "code exchange"

    (it "POSTs authorization_code through isaac.llm.http"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [url headers body & _]
                                            (reset! captured {:url url :headers headers :body body})
                                            {:access_token  "at-1"
                                             :refresh_token "rt-1"
                                             :expires_in    3600})]
          (let [tokens (sut/exchange-code! {:client-id     "isaac-test.apps.googleusercontent.com"
                                            :client-secret "shh"
                                            :redirect-uri  "http://localhost:9/"}
                                           "4/0AbCd")]
            (should= "at-1" (:access_token tokens))
            (should= sut/TOKEN-URL (:url @captured))
            (should= {"Content-Type" "application/json"} (:headers @captured))
            (should= {:grant_type    "authorization_code"
                      :code          "4/0AbCd"
                      :client_id     "isaac-test.apps.googleusercontent.com"
                      :client_secret "shh"
                      :redirect_uri  "http://localhost:9/"}
                     (:body @captured))))))
    )

  (context "refresh"

    (it "POSTs refresh_token through isaac.llm.http"
      (let [captured (atom nil)]
        (with-redefs [llm-http/post-json! (fn [url headers body & _]
                                            (reset! captured {:url url :headers headers :body body})
                                            {:access_token "at-2" :expires_in 3600})]
          (let [tokens (sut/refresh! {:client-id     "cid"
                                      :client-secret "shh"}
                                     "rt-1")]
            (should= "at-2" (:access_token tokens))
            (should= sut/TOKEN-URL (:url @captured))
            (should= {:grant_type    "refresh_token"
                      :refresh_token "rt-1"
                      :client_id     "cid"
                      :client_secret "shh"}
                     (:body @captured))))))
    )

  (context "invalid_grant"

    (it "names the Testing consent screen so operators know why to re-login"
      (let [msg (sut/invalid-grant-message)]
        (should-contain "consent screen" msg)
        (should-contain "Testing" msg)))
    )

  (context "seven-day token"

    (it "treats a 7-day expires_in as the Testing-mode refresh"
      (should (sut/seven-day-token? 604800))
      (should-not (sut/seven-day-token? 3600)))
    )
  )

(describe "isaac.google.scopes"

  (it "unions contributed scopes and always includes openid"
    (let [index {:isaac.google {:manifest {:isaac.google/scopes ["openid"]}}
                 :marigold.skybeam {:manifest {:isaac.google/scopes ["https://www.googleapis.com/auth/chat.messages.readonly"]}}}]
      (should= ["openid" "https://www.googleapis.com/auth/chat.messages.readonly"]
               (scopes/union index))))
  )
