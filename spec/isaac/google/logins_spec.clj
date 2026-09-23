(ns isaac.google.logins-spec
  (:require
    [isaac.fs :as fs]
    [isaac.google.logins :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer [around context describe it should should-be-nil should-not should-not-be-nil should=]])
  (:import
    (java.time Instant)))

(def ROOT "/test/isaac")

(defn- record [created-at]
  {:tenant        :tonotop
   :scopes        ["openid"]
   :code-verifier "v-1"
   :created-at    created-at})

(describe "pending Google logins"

  (around [example]
    (nexus/-with-nexus {:root ROOT :fs (fs/mem-fs)}
      (example)))

  (context "the nonces"

    (it "mints a state nobody can guess and nothing else has"
      (let [states (repeatedly 50 sut/state-nonce)]
        (should= 50 (count (set states)))
        (should (every? sut/state? states))))

    (it "mints a code verifier PKCE's length rule accepts"
      (let [verifier (sut/code-verifier)]
        (should (<= 43 (count verifier) 128))
        (should (re-matches #"[A-Za-z0-9_-]+" verifier))))

    ;; RFC 7636 S256: base64url(sha256(verifier)), unpadded. Google checks it,
    ;; so the known-answer vector from the RFC is the assertion.
    (it "challenges with the RFC's S256 digest of the verifier"
      (should= "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
               (sut/code-challenge "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")))

    (it "takes only a state it could have minted itself"
      (should (sut/state? "abcdefghij"))
      (should-not (sut/state? "../../etc/passwd"))
      (should-not (sut/state? "short"))
      (should-not (sut/state? nil))
      (should-not (sut/state? "has/slash/and.dots"))))

  (context "the file"

    (it "keeps one pending login per state under google/logins"
      (sut/save! ROOT "st-abcdefgh" (record "2026-09-23T12:00:00Z"))
      (should (fs/exists? (fs/instance) "/test/isaac/google/logins/st-abcdefgh.edn"))
      (should= (record "2026-09-23T12:00:00Z") (sut/pending ROOT "st-abcdefgh")))

    (it "has nothing for a state it never minted"
      (should-be-nil (sut/pending ROOT "st-nosuchstate"))
      (should-be-nil (sut/pending ROOT "../../secrets")))

    (it "forgets a login once it is spent"
      (sut/save! ROOT "st-abcdefgh" (record "2026-09-23T12:00:00Z"))
      (sut/forget! ROOT "st-abcdefgh")
      (should-be-nil (sut/pending ROOT "st-abcdefgh")))

    (it "forgets a state it never had without complaining"
      (should-be-nil (sut/forget! ROOT "st-nosuchstate"))))

  (context "the ten-minute window"

    (it "is live while the operator is still at the consent screen"
      (should-not (sut/expired? (record "2026-09-23T12:00:00Z")
                                (Instant/parse "2026-09-23T12:09:59Z"))))

    (it "is dead once ten minutes have passed"
      (should (sut/expired? (record "2026-09-23T12:00:00Z")
                            (Instant/parse "2026-09-23T12:10:01Z"))))

    ;; A record whose :created-at nobody can read is not a live door either.
    (it "treats an unreadable created-at as expired"
      (should (sut/expired? (record "whenever") (Instant/parse "2026-09-23T12:00:00Z")))
      (should (sut/expired? (dissoc (record nil) :created-at) (Instant/parse "2026-09-23T12:00:00Z"))))

    (it "reads back what it wrote, created-at included"
      (sut/save! ROOT "st-abcdefgh" (record "2026-09-23T12:00:00Z"))
      (should-not-be-nil (:created-at (sut/pending ROOT "st-abcdefgh"))))))
