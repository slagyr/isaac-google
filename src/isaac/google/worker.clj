(ns isaac.google.worker
  "Inbox worker: drain pending records onto :isaac.google/handler contributions.

   The door stamped each record with the Google organization that sent it, so
   the worker acts as that tenant while its handler runs — a handler asks for
   \"the token\" and gets the right organization's token without being told
   which (isaac-1zkz)."
  (:require
    [isaac.google.handler :as handler]
    [isaac.google.inbox :as inbox]
    [isaac.google.tenants :as tenants]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(defn tick!
  "Drain every pending record once on the caller thread."
  ([]
   (tick! (or (nexus/get :root))))
  ([root]
   (when root
     (doseq [event (inbox/pending root)]
       (let [message-id (:message-id event)
             type       (:type event)
             tenant     (or (:tenant event) tenants/DEFAULT)
             f          (handler/lookup type)]
         (cond
           (nil? f)
           (log/warn :google/handler-missing :message-id message-id :type type)

           :else
           (try
             (binding [tenants/*tenant* tenant]
               (f event))
             (inbox/mark-done! root message-id)
             (catch Exception e
               (log/error :google/handler-failed
                          :message-id message-id
                          :type type
                          :tenant tenant
                          :error (.getMessage e))
               (inbox/mark-failed! root message-id)))))))))
