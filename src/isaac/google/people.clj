(ns isaac.google.people
  "Who spoke, by a key that does not change. Chat hands out users/<id> and a
   display name and never an email; Gmail hands out an email and a name and
   never an id. Google is the source of truth for the join, so this namespace
   resolves on demand through the People API — nothing is persisted. An
   in-memory memo with a short TTL keeps a busy thread from re-asking per
   message. Every failure is soft: the caller gets back what it already knew."
  (:refer-clojure :exclude [resolve])
  (:require
    [clojure.string :as str]
    [isaac.google.events :as events]
    [isaac.logger :as log]))

(def BASE "https://people.googleapis.com/v1")

(def DIRECTORY-SCOPE
  "Scope that lets Isaac ask the Workspace directory for a user's email."
  "https://www.googleapis.com/auth/directory.readonly")

(def PERSON-FIELDS "names,emailAddresses")

(def TTL-SECONDS 3600)

(defonce ^:private memo* (atom {}))
(defonce ^:private warned* (atom #{}))

(defn reset-memo!
  "Drops the memo and the warn-once marks. Specs and feature steps call this."
  []
  (reset! memo* {})
  (reset! warned* #{}))

(defn now-ms
  "Clock seam in milliseconds."
  []
  (System/currentTimeMillis))

(defn warn!
  "Log seam so callers see a named warning once per reason."
  [event & kvs]
  (apply log/log* :warn event *file* nil kvs))

(defn- warn-once! [reason event & kvs]
  (when-not (contains? @warned* reason)
    (swap! warned* conj reason)
    (apply warn! event kvs)))

(defn- person-id
  "users/1234 → 1234; a bare id passes through."
  [user]
  (let [s (str user)]
    (if (str/includes? s "/")
      (last (str/split s #"/"))
      s)))

(defn fetch!
  "HTTP seam: People API people.get for a users/<id>. Feature steps redef this."
  [user]
  (events/request! {:method :get
                    :url    (str BASE "/people/" (person-id user))
                    :query  {:personFields PERSON-FIELDS}}))

(defn- primary-of [entries k]
  (let [entries (filter map? entries)]
    (or (some (fn [e] (when (get-in e [:metadata :primary]) (get e k))) entries)
        (some (fn [e] (get e k)) entries))))

(defn- scope-missing? [response]
  (let [message (str (:message response))]
    (or (= 403 (:status response))
        (str/includes? (str/lower-case message) "insufficient authentication scopes"))))

(defn- entry-from [user display-name body]
  {:user         (str user)
   :display-name (or (primary-of (:names body) :displayName)
                     (not-empty (str (or display-name "")))
                     nil)
   :email        (primary-of (:emailAddresses body) :value)})

(defn- fallback-entry [user display-name]
  {:user         (str user)
   :display-name (not-empty (str (or display-name "")))
   :email        nil})

(defn- lookup [user display-name]
  (let [response (try
                   (fetch! user)
                   (catch Exception e
                     {:error :auth-failed :message (.getMessage e)}))]
    (cond
      (not (map? response))
      (fallback-entry user display-name)

      (:error response)
      (do
        (if (scope-missing? response)
          (warn-once! :scope
                      :google.people/scope-missing
                      :scope DIRECTORY-SCOPE
                      :message (str "Google refused the people lookup for lack of "
                                    DIRECTORY-SCOPE
                                    " — re-run `isaac google login` to grant it."))
          (warn-once! :lookup
                      :google.people/lookup-failed
                      :status (:status response)
                      :message (str (:message response))))
        (fallback-entry user display-name))

      :else
      (entry-from user display-name response))))

(defn- fresh? [{:keys [at]}]
  (and at (< (- (now-ms) at) (* 1000 TTL-SECONDS))))

(defn resolve
  "users/<id> → {:user :display-name :email}. Asks Google at most once per
   TTL per id; never throws. On any failure the entry carries whatever the
   caller already knew (its :display-name) and no :email."
  ([user] (resolve user {}))
  ([user {:keys [display-name]}]
   (when (seq (str (or user "")))
     (let [cached (get @memo* (str user))]
       (if (fresh? cached)
         (let [entry (:entry cached)]
           (cond-> entry
             (and (nil? (:display-name entry)) (seq (str (or display-name ""))))
             (assoc :display-name display-name)))
         (let [entry (lookup user display-name)]
           (swap! memo* assoc (str user) {:at (now-ms) :entry entry})
           entry))))))

(defn render
  "\"Micah Martin <micah@tonotop.com>\" — degrades to whatever is known."
  [{:keys [display-name email user]}]
  (let [display-name (not-empty (str (or display-name "")))
        email        (not-empty (str (or email "")))]
    (cond
      (and display-name email) (str display-name " <" email ">")
      email                    email
      display-name             display-name
      :else                    (str user))))
