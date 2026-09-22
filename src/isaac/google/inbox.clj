(ns isaac.google.inbox
  "Durable Pub/Sub inbox: pending/done/failed records plus a bounded seen-set."
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(def seen-bound 4096)

(defn- runtime-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(defn- inbox-dir [root]
  (str root "/google/inbox"))

(defn- pending-path [root message-id]
  (str (inbox-dir root) "/pending/" message-id ".edn"))

(defn- done-path [root message-id]
  (str (inbox-dir root) "/done/" message-id ".edn"))

(defn- failed-path [root message-id]
  (str (inbox-dir root) "/failed/" message-id ".edn"))

(defn- seen-path [root]
  (str (inbox-dir root) "/seen.edn"))

(defn- read-edn [fs* path]
  (when (fs/exists? fs* path)
    (edn/read-string (fs/slurp fs* path))))

(defn- load-seen [root]
  (or (read-edn (runtime-fs) (seen-path root)) []))

(defn- save-seen! [root seen]
  (let [fs*  (runtime-fs)
        path (seen-path root)
        kept (vec (take-last seen-bound seen))]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (write-edn kept))
    kept))

(defn- remember! [root message-id]
  (let [seen (load-seen root)]
    (when-not (some #{message-id} seen)
      (save-seen! root (conj seen message-id)))))

(defn seen? [root message-id]
  (boolean (some #{message-id} (load-seen root))))

(defn accept!
  "Persist `event` under pending/<message-id>.edn. Returns :new or :duplicate."
  [root event]
  (let [message-id (:message-id event)
        fs*        (runtime-fs)
        path       (pending-path root message-id)]
    (if (or (seen? root message-id) (fs/exists? fs* path))
      :duplicate
      (do
        (fs/mkdirs fs* (fs/parent path))
        (fs/spit fs* path (write-edn event))
        (remember! root message-id)
        :new))))

(defn pending [root]
  (let [fs*  (runtime-fs)
        dir  (str (inbox-dir root) "/pending")]
    (if (fs/exists? fs* dir)
      (->> (fs/children fs* dir)
           (filter #(re-find #"\.edn$" %))
           (mapv #(read-edn fs* (str dir "/" %))))
      [])))

(defn- move! [root message-id dest-path]
  (let [fs*  (runtime-fs)
        from (pending-path root message-id)
        to   dest-path]
    (when (fs/exists? fs* from)
      (fs/mkdirs fs* (fs/parent to))
      (fs/move fs* from to))))

(defn mark-done! [root message-id]
  (move! root message-id (done-path root message-id)))

(defn mark-failed! [root message-id]
  (move! root message-id (failed-path root message-id)))

(defn status
  "Where message-id currently sits: :pending, :done, :failed, or :unknown (not
   yet arrived, or arrived and never accepted). Lets a caller outside the
   worker (the live smoke driver) poll a specific record's progress without
   knowing the inbox's directory layout."
  [root message-id]
  (let [fs* (runtime-fs)]
    (cond
      (fs/exists? fs* (pending-path root message-id)) :pending
      (fs/exists? fs* (done-path root message-id))    :done
      (fs/exists? fs* (failed-path root message-id))  :failed
      :else                                            :unknown)))
