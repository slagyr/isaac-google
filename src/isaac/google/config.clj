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

(def google-schema
  {:name        :google
   :type        :map
   :description "Shared Google Workspace plumbing — OAuth, Pub/Sub topic, GCP project."
   :schema      {:project {:type        :string
                           :description "GCP project id that owns the Pub/Sub topic."}
                 :topic   {:type        :string
                           :description "Pub/Sub topic path (projects/<id>/topics/<name>)."}
                 :oauth   oauth-schema}})
