(ns isaac.google.service-account-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.google.service-account :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer [around before context describe it should should-be-nil should-contain
                         should-not-contain should= with]])
  (:import
    (java.security KeyPairGenerator Signature)
    (java.time Instant)
    (java.util Base64)))

(def root "/test/google-sa")

(def key-path (str root "/google/pubsub-sa.json"))

(def ^:private key-pair
  (delay (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048)))))

(defn- pem []
  (str "-----BEGIN PRIVATE KEY-----\n"
       (.encodeToString (Base64/getMimeEncoder 64 (.getBytes "\n" "UTF-8"))
                        (.getEncoded (.getPrivate @key-pair)))
       "\n-----END PRIVATE KEY-----\n"))

(defn- key-json []
  (json/generate-string {:type           "service_account"
                         :project_id     "marigold"
                         :private_key_id "pk-1"
                         :private_key    (pem)
                         :client_email   "isaac-pubsub@marigold.iam.gserviceaccount.com"
                         :token_uri      sut/TOKEN-URL}))

(def config
  {:root   root
   :google {:tonotop {:topic  "projects/marigold/topics/isaac"
                      :pubsub {:credentials-file "google/pubsub-sa.json"}}
            :acme    {:topic "projects/acme-prod/topics/isaac"}}})

(def now (Instant/parse "2026-09-24T12:00:00Z"))

(defn- decode-segment [segment]
  (json/parse-string (String. (.decode (Base64/getUrlDecoder) (str segment)) "UTF-8") true))

(describe "isaac.google.service-account"

  (with mem (fs/mem-fs))

  (around [it]
    (nexus/-with-nested-nexus {:fs @mem :root root}
      (sut/reset-tokens!)
      (it)))

  (before (fs/spit @mem key-path (key-json)))

  (context "the credential"

    (it "reads the organization's key from the path it names, relative to the Isaac root"
      (let [{:keys [credentials error]} (sut/read-credentials config :tonotop)]
        (should-be-nil error)
        (should= "isaac-pubsub@marigold.iam.gserviceaccount.com" (:client_email credentials))))

    (it "names the config key an operator would set"
      (should= "google.tonotop.pubsub.credentials-file" (sut/credentials-key config :tonotop)))

    ;; Absent by default: an organization that never named a key has none, and
    ;; is told what to set rather than left to discover it on a publish.
    (it "says what is missing when the organization named no key"
      (let [{:keys [error]} (sut/read-credentials config :acme)]
        (should-contain "must name a service-account JSON key" error)
        (should-contain "never as the signed-in user" error)))

    (it "says so when the path names no file"
      (fs/delete @mem key-path)
      (should-contain "names no file" (:error (sut/read-credentials config :tonotop))))

    (it "says so when the file is not JSON"
      (fs/spit @mem key-path "not json at all")
      (should-contain "is not readable JSON" (:error (sut/read-credentials config :tonotop))))

    (it "says so when the JSON is not a service-account key"
      (fs/spit @mem key-path (json/generate-string {:hello "marigold"}))
      (should-contain "client_email and private_key" (:error (sut/read-credentials config :tonotop)))))

  (context "the signed assertion"

    (it "asks for the Pub/Sub scope, from the service account, at Google's token endpoint"
      (let [[header claims] (str/split (sut/assertion (:credentials (sut/read-credentials config :tonotop)) now) #"\.")
            header  (decode-segment header)
            claims  (decode-segment claims)]
        (should= "RS256" (:alg header))
        (should= "pk-1" (:kid header))
        (should= "isaac-pubsub@marigold.iam.gserviceaccount.com" (:iss claims))
        (should= sut/SCOPE (:scope claims))
        (should= sut/TOKEN-URL (:aud claims))
        (should= (.getEpochSecond now) (:iat claims))
        (should= (+ (.getEpochSecond now) sut/ASSERTION-TTL-SECONDS) (:exp claims))))

    (it "is signed by the key, so Google can tell it is ours"
      (let [jwt     (sut/assertion (:credentials (sut/read-credentials config :tonotop)) now)
            [h c s] (str/split jwt #"\.")
            verify  (doto (Signature/getInstance "SHA256withRSA")
                      (.initVerify (.getPublic @key-pair))
                      (.update (.getBytes (str h "." c) "UTF-8")))]
        (should (.verify verify (.decode (Base64/getUrlDecoder) s))))))

  (context "the token"

    (it "trades the assertion for an access token"
      (let [sent (atom nil)]
        (with-redefs [sut/exchange! (fn [uri assertion]
                                      (reset! sent {:uri uri :assertion assertion})
                                      {:access_token "sa-at-1" :expires_in 3600})]
          (should= {:access "sa-at-1"} (sut/resolve-token config :tonotop now)))
        (should= sut/TOKEN-URL (:uri @sent))
        (should= sut/SCOPE (:scope (decode-segment (second (str/split (:assertion @sent) #"\.")))))))

    ;; A heartbeat an hour is not worth a token exchange an hour.
    (it "spends a cached token until it is nearly gone"
      (let [calls (atom 0)]
        (with-redefs [sut/exchange! (fn [_ _] (swap! calls inc) {:access_token "sa-at-1" :expires_in 3600})]
          (sut/resolve-token config :tonotop now)
          (sut/resolve-token config :tonotop (.plusSeconds now 1800))
          (should= 1 @calls)
          (sut/resolve-token config :tonotop (.plusSeconds now 3599))
          (should= 2 @calls))))

    (it "reports Google's refusal rather than throwing"
      (with-redefs [sut/exchange! (fn [_ _] {:error :api-error :status 400
                                             :body  {:error_description "Invalid JWT Signature."}})]
        (should-contain "Invalid JWT Signature."
                        (:error (sut/resolve-token config :tonotop now)))))

    (it "reports a thrown exchange as an error"
      (with-redefs [sut/exchange! (fn [_ _] (throw (ex-info "connection reset" {})))]
        (should-contain "connection reset" (:error (sut/resolve-token config :tonotop now)))))

    (it "keys a missing credential by the config key that would fix it"
      (should-contain "google.acme.pubsub.credentials-file"
                      (:error (sut/resolve-token config :acme now))))

    (it "hands out headers bearing the service account, never a user token"
      (with-redefs [sut/exchange! (fn [_ _] {:access_token "sa-at-1" :expires_in 3600})]
        (should= {"Authorization" "Bearer sa-at-1" "Content-Type" "application/json"}
                 (:headers (sut/auth-headers config :tonotop)))))

    (it "hands out an error instead of headers when there is no credential"
      (let [{:keys [headers error]} (sut/auth-headers config :acme)]
        (should-be-nil headers)
        (should-contain "google.acme.pubsub.credentials-file" error))))

  ;; Without this the host starts, `isaac config validate` says OK, and the
  ;; first heartbeat an hour later is where it comes out (isaac-286x).
  (context "the config check"

    (it "refuses an organization with a topic and no service account"
      (let [{:keys [errors]} (sut/check-credentials {:config config})]
        (should= 1 (count errors))
        (should= "google.acme.pubsub.credentials-file" (:key (first errors)))
        (should-contain "must name a service-account JSON key" (:value (first errors)))))

    (it "passes an organization whose key is named and readable"
      (let [cfg {:root root :google {:tonotop (get-in config [:google :tonotop])}}]
        (should= [] (:errors (sut/check-credentials {:config cfg})))))

    ;; An organization with no topic publishes nothing, so it needs no
    ;; publishing identity — asking for one would be ceremony.
    (it "asks nothing of an organization with no topic"
      (let [cfg {:root root :google {:acme {:oauth {:client-id "cid"}}}}]
        (should= [] (:errors (sut/check-credentials {:config cfg})))))

    (it "reports a named key that is not there"
      (fs/delete @mem key-path)
      (let [cfg    {:root root :google {:tonotop (get-in config [:google :tonotop])}}
            errors (:errors (sut/check-credentials {:config cfg}))]
        (should= 1 (count errors))
        (should-contain "names no file" (:value (first errors)))))

    ;; A topic is also the address Google pushes TO, and receiving costs no
    ;; credential: Gmail and Chat keep working with no service account. Only
    ;; the heartbeat publishes on a timer, so only the heartbeat may demand
    ;; one. Where an organization forbids service-account keys outright
    ;; (constraints/iam.disableServiceAccountKeyCreation, yopp 2026-09-24),
    ;; turning the heartbeat off has to leave a host that still starts.
    (it "asks nothing of an organization whose heartbeat is off"
      (let [cfg (assoc-in {:root root :google {:acme (get-in config [:google :acme])}}
                          [:google :acme :health :heartbeat :enabled] false)]
        (should= [] (:errors (sut/check-credentials {:config cfg})))))

    (it "still refuses when the heartbeat is left on"
      (let [cfg (assoc-in {:root root :google {:acme (get-in config [:google :acme])}}
                          [:google :acme :health :heartbeat :enabled] true)]
        (should= 1 (count (:errors (sut/check-credentials {:config cfg})))))))

  (it "asks for no scope but Pub/Sub"
    (should= "https://www.googleapis.com/auth/pubsub" sut/SCOPE)
    (should-not-contain "cloud-platform" sut/SCOPE))
  )
