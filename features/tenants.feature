Feature: Several Google organizations on one Isaac
  Everything Google is per organization: the GCP project, the Pub/Sub topic,
  the push service account, the OAuth client, and the Google user Isaac signs
  in as. An organization is that complete set, not a namespace over one login.
  There is one way to write it, whether a host serves one organization or ten:
  `google.<organization>.*`. There is no flat form and no default organization,
  so a reader never has to ask which shape was meant, and a nested config is
  never reported as unknown keys.
  Beans: isaac-1zkz, isaac-okfj.

  Scenario: a one-organization host writes the same shape as a host with several
    Given an Isaac root at "target/test-state"
    And config:
      | google.marigold.project              | marigold                                     |
      | google.marigold.topic                | projects/marigold/topics/isaac               |
      | google.marigold.push.endpoint        | https://isaac.example/google/pubsub          |
      | google.marigold.push.service-account | pubsub-push@marigold.iam.gserviceaccount.com |
    And Google signs push tokens with a test key
    And the skybeam fixture module handles Google events of type "google.workspace.chat.message.v1.created"
    And the Isaac server is started
    And the Google runtime component is started
    When Google pushes message "m-one" of type "google.workspace.chat.message.v1.created" with data:
      """
      {"message": {"name": "spaces/AAA/messages/1"}}
      """
    Then the response status is 204
    And the log has entries matching:
      | level | event                 | principal              | tenant   |
      | :info | :google/push-received | :google-pubsub/marigold | :marigold |

  Scenario: the flat config isaac-1zkz accepted configures no organization at all
    Given an Isaac root at "target/test-state"
    And config:
      | google.topic                | projects/marigold/topics/isaac               |
      | google.push.endpoint        | https://isaac.example/google/pubsub          |
      | google.push.service-account | pubsub-push@marigold.iam.gserviceaccount.com |
    And Google signs push tokens with a test key
    And the skybeam fixture module handles Google events of type "google.workspace.chat.message.v1.created"
    And the Isaac server is started
    And the Google runtime component is started
    When Google pushes message "m-flat" of type "google.workspace.chat.message.v1.created" with data:
      """
      {"message": {"name": "spaces/AAA/messages/1"}}
      """
    Then the response status is 401
    And the isaac file "google/inbox/pending/m-flat.edn" does not exist
    When isaac is run with "google status"
    Then the exit code is 0
    And the stdout contains "No Google organization configured"

  Scenario: two organizations share the door, each proved by its own service account
    Given an Isaac root at "target/test-state"
    And config:
      | google.marigold.project              | marigold                                      |
      | google.marigold.topic                | projects/marigold/topics/isaac                |
      | google.marigold.push.endpoint        | https://isaac.example/google/pubsub           |
      | google.marigold.push.service-account | pubsub-push@marigold.iam.gserviceaccount.com  |
      | google.acme.project                 | acme-prod                                     |
      | google.acme.topic                   | projects/acme-prod/topics/isaac               |
      | google.acme.push.endpoint           | https://isaac.example/google/pubsub           |
      | google.acme.push.service-account    | pubsub-push@acme-prod.iam.gserviceaccount.com |
    And Google signs push tokens with a test key
    And the skybeam fixture module handles Google events of type "google.workspace.chat.message.v1.created"
    And the Isaac server is started
    And the Google runtime component is started
    When Google pushes message "m-marigold" of type "google.workspace.chat.message.v1.created" signed by "pubsub-push@marigold.iam.gserviceaccount.com" on "projects/marigold/subscriptions/isaac" with data:
      """
      {"message": {"name": "spaces/AAA/messages/1"}}
      """
    Then the response status is 204
    And the log has entries matching:
      | level | event                 | principal              | tenant   |
      | :info | :google/push-received | :google-pubsub/marigold | :marigold |
    When Google pushes message "m-acme" of type "google.workspace.chat.message.v1.created" signed by "pubsub-push@acme-prod.iam.gserviceaccount.com" on "projects/acme-prod/subscriptions/isaac" with data:
      """
      {"message": {"name": "spaces/BBB/messages/1"}}
      """
    Then the response status is 204
    And the log has entries matching:
      | level | event                 | principal           | tenant |
      | :info | :google/push-received | :google-pubsub/acme | :acme  |

  Scenario: one organization's service account cannot speak for another's subscription
    Given an Isaac root at "target/test-state"
    And config:
      | google.marigold.project              | marigold                                      |
      | google.marigold.push.endpoint        | https://isaac.example/google/pubsub           |
      | google.marigold.push.service-account | pubsub-push@marigold.iam.gserviceaccount.com  |
      | google.acme.project                 | acme-prod                                     |
      | google.acme.push.endpoint           | https://isaac.example/google/pubsub           |
      | google.acme.push.service-account    | pubsub-push@acme-prod.iam.gserviceaccount.com |
    And Google signs push tokens with a test key
    And the skybeam fixture module handles Google events of type "google.workspace.chat.message.v1.created"
    And the Isaac server is started
    And the Google runtime component is started
    When Google pushes message "m-crossed" of type "google.workspace.chat.message.v1.created" signed by "pubsub-push@marigold.iam.gserviceaccount.com" on "projects/acme-prod/subscriptions/isaac" with data:
      """
      {"message": {"name": "spaces/BBB/messages/2"}}
      """
    Then the response status is 403
    And the isaac file "google/inbox/pending/m-crossed.edn" does not exist
    And the log has entries matching:
      | level | event                   | subscription-tenant | principal-tenant |
      | :warn | :google/tenant-mismatch | :acme               | :marigold         |

  Scenario: each organization signs in for itself and status lists both
    Given an Isaac root at "target/test-state"
    And config:
      | google.marigold.oauth.client-id     | marigold.apps.googleusercontent.com |
      | google.marigold.oauth.client-secret | shh                                |
      | google.marigold.oauth.account       | isaac@marigold.example                   |
      | google.acme.oauth.client-id        | acme.apps.googleusercontent.com    |
      | google.acme.oauth.client-secret    | shh                                |
      | google.acme.oauth.account          | isaac@acme.example                 |
    And the Google token endpoint returns access token "at-marigold" and refresh token "rt-marigold" expiring in 3600
    When isaac is run with "google login --tenant marigold --code 4/AAA"
    Then the exit code is 0
    And the stdout contains "Signed in as isaac@marigold.example"
    Given the Google token endpoint returns access token "at-acme" and refresh token "rt-acme" expiring in 3600
    When isaac is run with "google login --tenant acme --code 4/BBB"
    Then the exit code is 0
    And the stdout contains "Signed in as isaac@acme.example"
    And the google auth store for tenant "marigold" has access "at-marigold" and refresh "rt-marigold"
    And the google auth store for tenant "acme" has access "at-acme" and refresh "rt-acme"
    When isaac is run with "google status"
    Then the exit code is 0
    And the stdout contains "tenant: marigold"
    And the stdout contains "tenant: acme"
