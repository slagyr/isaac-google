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
                                 :description "Google account the tokens belong to (e.g. yopp@tonotop.com)."}}})

(def push-schema
  {:name        :google-push
   :type        :map
   :description "Pub/Sub push door."
   :schema      {:endpoint        {:type        :string
                                   :description "HTTPS URL Google POSTs to (aud of the OIDC token)."}
                 :service-account {:type        :string
                                   :description "Push subscription service account email."}}})

(def health-schema
  {:name        :google-health
   :type        :map
   :description "Silence threshold for Google health checks."
   :schema      {:silent-after-hours {:type        :int
                                      :description "Hours without an event before a registration is silent. Default 6."}}})

(def google-schema
  {:name        :google
   :type        :map
   :description "Shared Google Workspace plumbing — OAuth, Pub/Sub topic, GCP project."
   :schema      {:project             {:type        :string
                                       :description "GCP project id that owns the Pub/Sub topic."}
                 :topic               {:type        :string
                                       :description "Pub/Sub topic path (projects/<id>/topics/<name>)."}
                 :renew-within-hours {:type        :int
                                       :description "Hours before expiry at which the registration timer renews a subscription. Default 24."}
                 :health              health-schema
                 :oauth               oauth-schema
                 :push                push-schema}})
