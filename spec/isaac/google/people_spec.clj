(ns isaac.google.people-spec
  (:require
    [isaac.google.people :as sut]
    [isaac.google.tenants :as tenants]
    [speclj.core :refer :all]))

(def hieronymus
  {:names          [{:displayName "Hieronymus Finch" :metadata {:primary true}}]
   :emailAddresses [{:value "hieronymus@marigold.example" :metadata {:primary true}}]})

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
      (let [[entry calls] (with-fetch hieronymus #(sut/resolve "users/1234"))]
        (should= "users/1234" (:user entry))
        (should= "Hieronymus Finch" (:display-name entry))
        (should= "hieronymus@marigold.example" (:email entry))
        (should= ["users/1234"] calls)))

    (it "asks Google once inside the memo TTL"
      (let [[_ calls] (with-fetch hieronymus #(do (sut/resolve "users/1234")
                                             (sut/resolve "users/1234")))]
        (should= ["users/1234"] calls)))

    (it "asks Google again once the memo entry is older than the TTL"
      (let [clock (atom 0)
            calls (atom [])]
        (with-redefs [sut/now-ms (fn [] @clock)
                      sut/fetch! (fn [user] (swap! calls conj user) hieronymus)]
          (sut/resolve "users/1234")
          (reset! clock (* 1000 (inc sut/TTL-SECONDS)))
          (sut/resolve "users/1234"))
        (should= ["users/1234" "users/1234"] @calls)))

    (it "fails soft when the lookup errors: no email, the known display name kept"
      (let [[entry _] (with-fetch {:error :api-error :status 500 :message "boom"}
                        #(sut/resolve "users/1234" {:display-name "Hieronymus"}))]
        (should= "users/1234" (:user entry))
        (should= "Hieronymus" (:display-name entry))
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

  ;; Google ids are global, but the directory answer is not: each organization
  ;; sees its own people with its own token, so one org's answer must never be
  ;; served to another (isaac-1zkz).
  (context "several organizations"

    (it "records the organization the sighting came from"
      (let [[entry _] (binding [tenants/*tenant* :acme]
                        (with-fetch hieronymus #(sut/resolve "users/1234")))]
        (should= :acme (:tenant entry))))

    (it "asks each organization for itself rather than reusing the other's answer"
      (let [calls (atom [])]
        (with-redefs [sut/fetch! (fn [user] (swap! calls conj [tenants/*tenant* user]) hieronymus)]
          (binding [tenants/*tenant* :marigold] (sut/resolve "users/1234"))
          (binding [tenants/*tenant* :acme] (sut/resolve "users/1234"))
          (binding [tenants/*tenant* :acme] (sut/resolve "users/1234")))
        (should= [[:marigold "users/1234"] [:acme "users/1234"]] @calls))))

  (context "render"

    (it "renders name and email"
      (should= "Hieronymus Finch <hieronymus@marigold.example>"
               (sut/render {:display-name "Hieronymus Finch" :email "hieronymus@marigold.example"})))

    (it "renders the email alone when the name is unknown"
      (should= "hieronymus@marigold.example" (sut/render {:email "hieronymus@marigold.example"})))

    (it "renders the name alone when the email is unknown"
      (should= "Hieronymus" (sut/render {:display-name "Hieronymus" :user "users/1234"})))

    (it "falls back to the users/<id>"
      (should= "users/1234" (sut/render {:user "users/1234"})))))
