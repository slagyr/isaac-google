Feature: Pub/Sub publishes as a service account
  Subscribing to a topic is not something a person consents to.
  `https://www.googleapis.com/auth/pubsub` is a Google *Cloud Platform* scope,
  and a Workspace that configures "Google Cloud console and SDK session
  control" applies its reauthentication frequency — 16 hours by default — to
  every app requiring one, non-Google apps explicitly included. Asking for it
  on the login therefore put Gmail, Chat and the directory under the same
  clock: yopp's refresh token died after 14h50m, survived a re-login, and died
  again on the same cycle, with the stored token byte-identical throughout
  because Google revoked it server-side.
  So the publishing identity is a service account of Isaac's own, named by
  path in config, absent by default — and its absence is a config error at
  load, not a surprise on the first heartbeat an hour after a clean start.
  Beans: isaac-ey6q, isaac-286x.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: an organization with a topic and no service account is a config error
    Given config:
      | google.tonotop.topic | projects/marigold/topics/isaac |
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr matches:
      | pattern                                                 |
      | google\.tonotop\.pubsub\.credentials-file                |
      | must name a service-account JSON key                     |

  Scenario: a key file that is not there is named, not discovered on the first heartbeat
    Given config:
      | google.tonotop.topic                   | projects/marigold/topics/isaac |
      | google.tonotop.pubsub.credentials-file | google/pubsub-sa.json          |
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr matches:
      | pattern                                   |
      | google\.tonotop\.pubsub\.credentials-file |
      | names no file                             |

  Scenario: a file that is not a service-account key is named as such
    Given config:
      | google.tonotop.topic                   | projects/marigold/topics/isaac |
      | google.tonotop.pubsub.credentials-file | google/pubsub-sa.json          |
    And the isaac file "google/pubsub-sa.json" exists with:
      """
      {"hello": "marigold"}
      """
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr matches:
      | pattern                              |
      | client_email and private_key         |

  Scenario: an organization that names a readable key is asked for nothing more
    Given config:
      | google.tonotop.topic                   | projects/marigold/topics/isaac |
      | google.tonotop.pubsub.credentials-file | google/pubsub-sa.json          |
    And a Pub/Sub service-account key at "google/pubsub-sa.json"
    When isaac is run with "config validate"
    Then the exit code is 0
    And the stderr does not contain "pubsub.credentials-file"

  # An organization with no topic publishes nothing, so it needs no publishing
  # identity. Asking for one would be ceremony — and would break every host
  # that configures Google only to sign in.
  Scenario: an organization with no topic is asked for nothing
    Given config:
      | google.tonotop.oauth.client-id     | isaac-test.apps.googleusercontent.com |
      | google.tonotop.oauth.client-secret | shh                                   |
    When isaac is run with "config validate"
    Then the exit code is 0
    And the stderr does not contain "pubsub.credentials-file"
