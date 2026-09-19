(ns isaac.google.events
  "Workspace Events API client (subscriptions.create/get/patch/delete/list)."
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [isaac.google.oauth :as oauth]
    [isaac.google.token :as token]))

(def BASE "https://workspaceevents.googleapis.com/v1")

(defn- parse-body [body]
  (cond
    (nil? body) {}
    (map? body) body
    (string? body) (try (json/parse-string body true) (catch Exception _ body))
    :else body))

(defn- auth-headers []
  {"Authorization" (str "Bearer " (token/token))
   "Content-Type"  "application/json"})

(defn- error-result [status body]
  {:error    (if (= 401 status) :auth-failed :api-error)
   :status   status
   :body     body
   :message  (or (get-in body [:error :message])
                 (get-in body [:error])
                 (str status))})

(defn request!
  "HTTP seam. Feature steps redef this. `method` is :get :post :patch :delete."
  [{:keys [method url headers body query]}]
  (let [headers (or headers (auth-headers))
        opts    (cond-> {:headers headers :throw false}
                  query (assoc :query-params query)
                  (and body (not= method :get) (not= method :delete))
                  (assoc :body (if (string? body) body (json/generate-string body))))
        resp    (case method
                  :get    (http/get url opts)
                  :post   (http/post url opts)
                  :patch  (http/patch url opts)
                  :delete (http/delete url opts))
        parsed  (parse-body (:body resp))
        status  (:status resp)]
    (if (>= status 400)
      (error-result status parsed)
      parsed)))

(def LIST-FILTER
  "subscriptions.list refuses a request without a filter naming at least one event
   type (400 'Invalid or unsupported query filter'). Every Chat subscription we
   create carries message.created, so filtering on it lists them all."
  "event_types:\"google.workspace.chat.message.v1.created\"")

(defn list-subscriptions! []
  (request! {:method :get :url (str BASE "/subscriptions") :query {:filter LIST-FILTER}}))

(defn create-subscription!
  "subscriptions.create answers with a long-running Operation. When it is already
   done the subscription rides inside :response; hand that back so the caller sees
   the subscription's name and expireTime, not operations/…."
  [body]
  (let [result (request! {:method :post :url (str BASE "/subscriptions") :body body})]
    (if (and (map? result) (map? (:response result)))
      (merge (dissoc result :name :response :done :metadata) (:response result))
      result)))

(defn renew-subscription! [name]
  (request! {:method :patch
             :url    (str BASE "/" name)
             :query  {:updateMask "ttl"}
             :body   {:ttl (str oauth/SEVEN-DAY-SECONDS "s")}}))

(defn delete-subscription! [name]
  (request! {:method :delete :url (str BASE "/" name)}))
