(ns isaac.google.handler
  "Berth :isaac.google/handler — event-type → (fn [event]).")

(defonce ^:private handlers* (atom {}))

(defn reset-handlers! []
  (reset! handlers* {}))

(defn register-handler!
  "Per-entry factory. Entry is [type handler-fn-or-symbol]."
  [[event-type handler]]
  (let [f (cond
            (fn? handler) handler
            (var? handler) @handler
            (symbol? handler) (some-> (requiring-resolve handler) deref)
            :else handler)]
    (swap! handlers* assoc (name event-type) f)
    event-type))

(defn lookup [event-type]
  (get @handlers* (name (or event-type ""))))
