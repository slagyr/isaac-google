(ns isaac.google.config)

(def oauth-schema
  {:name        :google-oauth
   :type        :map
   :description "OAuth client for the Google user (authorization-code flow)."
   :schema      {:client-id     {:type        :string
                                 :validations [:present?]
                                 :description "OAuth client id from Google Cloud Console."}
                 :client-secret {:type        :string
                                 :validations [:present?]
                                 :description "OAuth client secret. Prefer ${GOOGLE_CLIENT_SECRET} from .env — never plaintext."}
                 :account       {:type        :string
                                 :description "Google account the tokens belong to (e.g. yopp@tonotop.com)."}
                 :redirect-base {:type        :string
                                 :description "Public base URL the login redirects back to, as https://<host>; the callback is <base>/google/oauth/callback and must be an Authorized redirect URI on a Web-application OAuth client. Unset, the login pastes a code (works with a Desktop client)."}}})

(def push-schema
  {:name        :google-push
   :type        :map
   :description "Pub/Sub push door."
   :schema      {:endpoint        {:type        :string
                                   :description "HTTPS URL Google POSTs to (aud of the OIDC token)."}
                 :service-account {:type        :string
                                   :description "Push subscription service account email."}}})

(def heartbeat-schema
  {:name        :google-heartbeat
   :type        :map
   :description "Pub/Sub heartbeat: a Cloud Scheduler job publishes one message to this organization's topic on a schedule and Isaac watches for it at the push door. Isaac publishes nothing — naming the interval is what turns the watch on."
   :schema      {:expected-interval-ms {:type        :int
                                        :description "How often the external publisher (a Cloud Scheduler job on the topic) publishes a heartbeat, in milliseconds. Setting it is what turns the watch on; no heartbeat arriving for this long plus :grace-ms is :heartbeat-missed. No default — only the schedule the publisher runs on can say what late means."}
                 :grace-ms             {:type        :int
                                        :description "Milliseconds of slack past the expected arrival — scheduler jitter plus Pub/Sub delivery — before a heartbeat counts as missed. Default 60000."}
                 :enabled              {:type        :boolean
                                        :validations [[:retired? "the heartbeat is published from outside Isaac now — set health.heartbeat.expected-interval-ms to the schedule the Cloud Scheduler job publishes on, or unset the heartbeat entirely (isaac-clly)"]]
                                        :description "Retired. Naming :expected-interval-ms is what turns the watch on; a switch that could be on over an interval nobody set is the inert watchdog this replaced."}}})

(def health-schema
  {:name        :google-health
   :type        :map
   :description "Health thresholds for one Google organization: how long it may be silent, and the externally-published heartbeat it watches for."
   :schema      {:silent-after-hours {:type        :int
                                      :description "Hours without any event from Google for this organization before it is silent. Default 6."}
                 :heartbeat          heartbeat-schema}})

(def tenant-fields
  "One Google organization's complete set: its project, topic, the OAuth
   client (and so the Google user Isaac signs in as) and its push service
   account. Nothing here publishes, so nothing here names a publishing
   identity (isaac-clly)."
  {:project            {:type        :string
                        :description "GCP project id that owns the Pub/Sub topic."}
   :topic              {:type        :string
                        :description "Pub/Sub topic path (projects/<id>/topics/<name>)."}
   :renew-within-hours {:type        :int
                        :description "Hours before expiry at which the registration timer renews a subscription. Default 24."}
   :health             health-schema
   :oauth              oauth-schema
   :push               push-schema})

(def tenant-schema
  {:name        :google-tenant
   :type        :map
   :message     "must be a map of one Google organization's config — :google is a map of organization id to config, e.g. google.tonotop.oauth.client-id"
   :description "One Google organization Isaac serves."
   :schema      tenant-fields})

(def google-schema
  "One shape: `:google` is a map of organization id to that organization's
   complete set, whether the host serves one organization or several. There is
   no flat form and no default organization, so there is nothing to mis-apply:
   every key under :google is an organization id, and its value is validated
   against :value-spec (isaac-okfj, closing isaac-pvfq)."
  {:name        :google
   :type        :map
   :description "Shared Google Workspace plumbing, per Google organization — OAuth, Pub/Sub topic, GCP project. A map of organization id to that organization's config."
   :key-spec    {:type :keyword}
   :value-spec  tenant-schema})
