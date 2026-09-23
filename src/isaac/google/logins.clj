(ns isaac.google.logins
  "Pending host-completed logins — one file per consent round-trip.

   `isaac google login` writes <root>/google/logins/<state>.edn before it
   prints the consent URL, and the callback door reads it back to learn which
   organization is signing in and which PKCE verifier proves the code came
   from this login and no other. The file is the whole handshake: the state
   nonce names it, it is good for ten minutes, and the callback deletes it
   once the tokens are stored, so a state opens the door exactly once
   (isaac-2abl).

   A state arrives from the public internet, so it is a file name only when
   it looks like one this module minted: nothing else is turned into a path."
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus])
  (:import
    (java.security MessageDigest SecureRandom)
    (java.time Duration Instant)
    (java.util Base64)))

(def TTL
  "How long the operator has to finish at the consent screen. A tab left open
   overnight must not still be a door into this host."
  (Duration/ofMinutes 10))

(def ^:private entropy (SecureRandom.))

(defn- b64url [^bytes bytes]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes))

(defn- nonce [byte-count]
  (let [bytes (byte-array byte-count)]
    (.nextBytes entropy bytes)
    (b64url bytes)))

(defn state-nonce
  "The `state` the consent URL carries and the callback comes back with."
  []
  (nonce 24))

(defn code-verifier
  "The PKCE secret this login keeps to itself until the code exchange. 48
   random bytes is 64 base64url characters — inside RFC 7636's 43..128."
  []
  (nonce 48))

(defn code-challenge
  "S256, as PKCE (RFC 7636 §4.2) spells it: base64url of the verifier's
   SHA-256, unpadded. What the consent URL shows Google."
  [verifier]
  (b64url (.digest (MessageDigest/getInstance "SHA-256")
                   (.getBytes (str verifier) "UTF-8"))))

(defn state?
  "Is this a state this module could have minted? Anything else never
   becomes a path."
  [state]
  (boolean (and state (re-matches #"[A-Za-z0-9_-]{8,128}" (str state)))))

(defn- runtime-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn dir [root]
  (str root "/google/logins"))

(defn path
  "Where `state`'s pending login lives, or nil when that is no state of ours."
  [root state]
  (when (state? state)
    (str (dir root) "/" state ".edn")))

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn save!
  "Record a login waiting for its callback: {:tenant :scopes :code-verifier
   :created-at}."
  [root state record]
  (when-let [path (path root state)]
    (let [fs* (runtime-fs)]
      (fs/mkdirs fs* (fs/parent path))
      (fs/spit fs* path (write-edn record))
      record)))

(defn pending
  "The login `state` names, or nil when no login is waiting under it."
  [root state]
  (when-let [path (path root state)]
    (let [fs* (runtime-fs)]
      (when (fs/exists? fs* path)
        (try
          (edn/read-string (fs/slurp fs* path))
          (catch Exception _ nil))))))

(defn forget!
  "Drop the pending login — spent, or too old to spend."
  [root state]
  (when-let [path (path root state)]
    (let [fs* (runtime-fs)]
      (when (fs/exists? fs* path)
        (fs/delete fs* path))))
  nil)

(defn expired?
  "Has this login's ten minutes run out? A created-at nobody can read is not
   a live window either."
  [record now]
  (let [created (try (Instant/parse (str (:created-at record)))
                     (catch Exception _ nil))]
    (or (nil? created)
        (.isAfter ^Instant now (.plus ^Instant created TTL)))))
