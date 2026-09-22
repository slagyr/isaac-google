(ns isaac.google.http-spec
  (:require
    [cheshire.core :as json]
    [isaac.fs :as fs]
    [isaac.google.health :as health]
    [isaac.google.heartbeat :as heartbeat]
    [isaac.google.http :as sut]
    [isaac.google.inbox :as inbox]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)
    (java.util Base64)))

(def two-tenant-config
  {:google {:tonotop {:project "marigold"}
            :acme    {:project "acme-prod"}}})

(defn- b64-json [m]
  (.encodeToString (Base64/getEncoder) (.getBytes (json/generate-string m) "UTF-8")))

(defn- push-request [{:keys [id subscription principal config]}]
  {:isaac/principal (when principal {:name principal})
   :isaac/config    config
   :body            (json/generate-string
                      {:subscription subscription
                       :message      {:messageId id
                                      :data      (b64-json {:message {:name "spaces/AAA/messages/1"}})
                                      :attributes {"ce-type" "google.workspace.chat.message.v1.created"}}})})

(defn- heartbeat-request [{:keys [id subscription principal config]}]
  {:isaac/principal (when principal {:name principal})
   :isaac/config    config
   :body            (json/generate-string
                      {:subscription subscription
                       :message      {:messageId  id
                                      :data       (b64-json {:isaac-heartbeat true})
                                      :attributes {"ce-type" heartbeat/CE-TYPE}}})})

(describe "google push door"

  (around [example]
    (binding [memory/*now* (Instant/parse "2026-09-18T12:00:00Z")]
      (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
        (log/capture-logs (example)))))

  (context "one door, several organizations (isaac-1zkz)"

    (it "stamps the accepted event with the tenant that sent it"
      (let [response (sut/handler (push-request {:id           "m-1"
                                                 :subscription "projects/acme-prod/subscriptions/isaac"
                                                 :principal    :google-pubsub/acme
                                                 :config       two-tenant-config}))]
        (should= 204 (:status response))
        (should= [:acme] (mapv :tenant (inbox/pending "/test/isaac")))))

    (it "keeps each organization's push under its own tenant"
      (sut/handler (push-request {:id           "m-2"
                                  :subscription "projects/marigold/subscriptions/isaac"
                                  :principal    :google-pubsub/tonotop
                                  :config       two-tenant-config}))
      (sut/handler (push-request {:id           "m-3"
                                  :subscription "projects/acme-prod/subscriptions/isaac"
                                  :principal    :google-pubsub/acme
                                  :config       two-tenant-config}))
      (should= [:acme :tonotop]
               (sort (mapv :tenant (inbox/pending "/test/isaac")))))

    ;; Acme's service account proved itself, but the push arrived on tonotop's
    ;; subscription. One of the two claims is false; keep nothing.
    (it "refuses a push whose subscription and service account disagree"
      (let [response (sut/handler (push-request {:id           "m-4"
                                                 :subscription "projects/marigold/subscriptions/isaac"
                                                 :principal    :google-pubsub/acme
                                                 :config       two-tenant-config}))]
        (should= 403 (:status response))
        (should= [] (inbox/pending "/test/isaac"))
        (should= 1 (count (filter #(= :google/tenant-mismatch (:event %)) @log/captured-logs)))))

    ;; One organization is written the same way as several, so its pushes are
    ;; stamped with its own id — there is no default (isaac-okfj).
    (it "stamps a one-organization host's push with that organization"
      (sut/handler (push-request {:id           "m-5"
                                  :subscription "projects/marigold/subscriptions/isaac"
                                  :principal    :google-pubsub/tonotop
                                  :config       {:google {:tonotop {:project "marigold"}}}}))
      (should= [:tonotop] (mapv :tenant (inbox/pending "/test/isaac"))))

    ;; The flat shape isaac-1zkz accepted names no organization, so nothing on
    ;; this host could own the push. Refuse it rather than keep an event no
    ;; organization answers for (isaac-okfj).
    (it "refuses a push when no organization is configured"
      (let [response (sut/handler (push-request {:id           "m-6"
                                                 :subscription "projects/marigold/subscriptions/isaac"
                                                 :principal    :google-pubsub/tonotop
                                                 :config       {:google {:project "marigold"}}}))]
        (should= 401 (:status response))
        (should= [] (inbox/pending "/test/isaac"))
        (should= 1 (count (filter #(= :google/no-organization (:event %)) @log/captured-logs))))))

  ;; The tick's own synthetic message proves door, verification and inbox
  ;; without anyone talking. It is answered like any push and then stops:
  ;; nothing pending, no handler, no turn — and it is never mistaken for an
  ;; event, or it would quiet the very silence watch it serves (isaac-an14).
  (context "the synthetic heartbeat (isaac-an14)"

    (it "records the heartbeat and keeps nothing for the worker"
      (let [response (sut/handler (heartbeat-request {:id           "hb-1"
                                                      :subscription "projects/marigold/subscriptions/isaac"
                                                      :principal    :google-pubsub/tonotop
                                                      :config       two-tenant-config}))]
        (should= 204 (:status response))
        (should= [] (inbox/pending "/test/isaac"))
        (should= {:tonotop "2026-09-18T12:00:00Z"}
                 (:last-heartbeat-at (health/load-state "/test/isaac")))))

    (it "does not count as an event from Google"
      (sut/handler (heartbeat-request {:id           "hb-2"
                                       :subscription "projects/marigold/subscriptions/isaac"
                                       :principal    :google-pubsub/tonotop
                                       :config       two-tenant-config}))
      (let [state (health/load-state "/test/isaac")]
        (should= nil (:last-event-at state))
        (should= "2026-09-18T12:00:00Z" (:door-last-hit state)))))
  )
