(ns isaac.google.people-spec
  (:require
    [isaac.google.people :as sut]
    [speclj.core :refer :all]))

(def micah
  {:names          [{:displayName "Micah Martin" :metadata {:primary true}}]
   :emailAddresses [{:value "micah@tonotop.com" :metadata {:primary true}}]})

(defn- with-fetch [response f]
  (let [calls (atom [])]
    (with-redefs [sut/fetch! (fn [user]
                               (swap! calls conj user)
                               response)]
      [(f) @calls])))

(describe "google people"

  (before (sut/reset-memo!))

  (context "resolve"

    (it "answers the display name and Workspace email for a users/<id>"
      (let [[entry calls] (with-fetch micah #(sut/resolve "users/1234"))]
        (should= "users/1234" (:user entry))
        (should= "Micah Martin" (:display-name entry))
        (should= "micah@tonotop.com" (:email entry))
        (should= ["users/1234"] calls)))

    (it "asks Google once inside the memo TTL"
      (let [[_ calls] (with-fetch micah #(do (sut/resolve "users/1234")
                                             (sut/resolve "users/1234")))]
        (should= ["users/1234"] calls)))

    (it "asks Google again once the memo entry is older than the TTL"
      (let [clock (atom 0)
            calls (atom [])]
        (with-redefs [sut/now-ms (fn [] @clock)
                      sut/fetch! (fn [user] (swap! calls conj user) micah)]
          (sut/resolve "users/1234")
          (reset! clock (* 1000 (inc sut/TTL-SECONDS)))
          (sut/resolve "users/1234"))
        (should= ["users/1234" "users/1234"] @calls)))

    (it "fails soft when the lookup errors: no email, the known display name kept"
      (let [[entry _] (with-fetch {:error :api-error :status 500 :message "boom"}
                        #(sut/resolve "users/1234" {:display-name "Micah"}))]
        (should= "users/1234" (:user entry))
        (should= "Micah" (:display-name entry))
        (should-be-nil (:email entry))))

    (it "fails soft when the seam throws"
      (with-redefs [sut/fetch! (fn [_] (throw (ex-info "no token" {})))]
        (should-be-nil (:email (sut/resolve "users/1234")))))

    (it "warns once, naming the missing scope, when Google refuses for lack of it"
      (let [warns (atom [])]
        (with-redefs [sut/warn! (fn [event & kvs] (swap! warns conj (into [event] kvs)))
                      sut/fetch! (fn [_] {:error  :api-error
                                          :status 403
                                          :message "Request had insufficient authentication scopes."})]
          (sut/resolve "users/1234")
          (sut/resolve "users/5678"))
        (should= 1 (count @warns))
        (should= :google.people/scope-missing (ffirst @warns))
        (should-contain sut/DIRECTORY-SCOPE (pr-str (first @warns))))))

  (context "render"

    (it "renders name and email"
      (should= "Micah Martin <micah@tonotop.com>"
               (sut/render {:display-name "Micah Martin" :email "micah@tonotop.com"})))

    (it "renders the email alone when the name is unknown"
      (should= "micah@tonotop.com" (sut/render {:email "micah@tonotop.com"})))

    (it "renders the name alone when the email is unknown"
      (should= "Micah" (sut/render {:display-name "Micah" :user "users/1234"})))

    (it "falls back to the users/<id>"
      (should= "users/1234" (sut/render {:user "users/1234"})))))
