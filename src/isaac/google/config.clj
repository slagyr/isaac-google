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

(def tenant-fields
  "One Google organization's complete set: its project, topic, OAuth client
   (and so the Google user Isaac signs in as) and push service account."
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
   :description "One Google organization Isaac serves."
   :schema      tenant-fields})

(def google-schema
  "Flat or tenanted, in one spec.

   The declared fields are one organization's settings — a single-organization
   host writes them straight under :google, as it always has, and reads as
   tenant :default. Any other key is a tenant id whose value is that tenant's
   own complete set. The foundation validates the declared fields closed and
   descends into every other key against :value-spec (isaac-1zkz)."
  (merge tenant-schema
         {:name        :google
          :description "Shared Google Workspace plumbing — OAuth, Pub/Sub topic, GCP project. Flat for one organization; a map of tenant id -> organization for several."
          :key-spec    {:type :keyword}
          :value-spec  tenant-schema}))
