(ns isaac.google.tools-spec
  (:require
    [isaac.google.people :as people]
    [isaac.google.tools :as sut]
    [speclj.core :refer :all]))

(def micah
  {:resourceName "people/118"
   :names        [{:displayName "Micah Martin"}]
   :emailAddresses [{:value "micah@tonotop.com"}]})

(describe "google__whois (isaac-jqk2)"

  (before (people/reset-memo!))

  (it "refuses an empty argument, naming what it wants"
    (let [result (sut/whois {})]
      (should (:isError result))
      (should-contain "users/<id>" (:error result))))

  (it "answers who a users/<id> is"
    (with-redefs [people/fetch! (fn [_] {:names [{:displayName "Micah Martin"}]
                                         :emailAddresses [{:value "micah@tonotop.com"}]})]
      (should= {:user "users/118" :display-name "Micah Martin" :email "micah@tonotop.com"}
               (:result (sut/whois {:who "users/118"})))))

  (it "answers which id an email is"
    (with-redefs [sut/search-directory! (fn [_] {:people [{:person micah}]})]
      (should= {:user "people/118" :display-name "Micah Martin" :email "micah@tonotop.com"}
               (:result (sut/whois {:who "micah@tonotop.com"})))))

  (it "answers the email back when the directory knows nobody by it"
    (with-redefs [sut/search-directory! (fn [_] {:people []})]
      (should= {:email "stranger@example.com" :display-name nil :user nil}
               (:result (sut/whois {:who "stranger@example.com"})))))

  (it "reports a directory failure as a tool error, not an exception"
    (with-redefs [sut/search-directory! (fn [_] {:error :api-error :status 403 :message "insufficient scopes"})]
      (let [result (sut/whois {:who "micah@tonotop.com"})]
        (should (:isError result))
        (should-contain "insufficient scopes" (:error result)))))

  (it "takes its argument under any case"
    (with-redefs [people/fetch! (fn [_] {:names [{:displayName "Micah Martin"}]})]
      (should= "Micah Martin" (:display-name (:result (sut/whois {"Who" "users/118"}))))))

  (it "describes itself for the model"
    (let [tool (sut/whois-tool-factory {})]
      (should-contain "users/<id>" (:description tool))
      (should= ["who"] (:required (:parameters tool)))))
  )
