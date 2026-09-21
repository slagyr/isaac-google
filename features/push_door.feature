Feature: Google Pub/Sub push door
  Google pushes every subscription's events to one Isaac door. The door
  is an identity source under per-principal auth (isaac-gym1): Google's
  OIDC token for this door — signed by a key at Google's JWKS, issued by
  accounts.google.com, audience the configured endpoint, from the
  configured push service account — is principal google-pubsub/<organization>
  with scope :google/push and nothing more. isaac-http owns the verification
  (isaac-4sqh); this module registers one trust rule per configured Google
  organization at start. Accepted events are persisted and answered 204
  before any handler runs; an inbox worker hands them to the handler a module
  contributed for the event type.
  Beans: isaac-1jep, isaac-x37l, isaac-okfj.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | google.tonotop.topic                | projects/marigold/topics/isaac               |
      | google.tonotop.push.endpoint        | https://isaac.example/google/pubsub          |
      | google.tonotop.push.service-account | pubsub-push@marigold.iam.gserviceaccount.com |
    And Google signs push tokens with a test key
    And the skybeam fixture module handles Google events of type "google.workspace.chat.message.v1.created"
    And the Isaac server is started
    And the Google runtime component is started

  Scenario: a valid push is persisted and acknowledged before any handler runs
    When Google pushes message "m-1" of type "google.workspace.chat.message.v1.created" with data:
      """
      {"message": {"name": "spaces/AAA/messages/1"}}
      """
    Then the response status is 204
    And the isaac file "google/inbox/pending/m-1.edn" exists
    And the skybeam handler received no messages
    And the log has entries matching:
      | level | event                 | principal              | message-id |
      | :info | :google/push-received | :google-pubsub/tonotop | m-1        |
    When the inbox worker ticks
    Then the skybeam handler received message "m-1"
    And the isaac file "google/inbox/done/m-1.edn" exists

  Scenario: anything but Google's token for this door is refused and nothing is kept
    Given the next push token has audience "https://elsewhere.example/hook"
    When Google pushes message "m-2" of type "google.workspace.chat.message.v1.created" with data:
      """
      {}
      """
    Then the response status is 401
    And the isaac file "google/inbox/pending/m-2.edn" does not exist
    And the log has entries matching:
      | event         | reason    |
      | :auth/refused | :audience |
    Given the next push token is from "someone-else@marigold.iam.gserviceaccount.com"
    When Google pushes message "m-3" of type "google.workspace.chat.message.v1.created" with data:
      """
      {}
      """
    Then the response status is 401
    And the log has entries matching:
      | event         | reason  |
      | :auth/refused | :claims |
    Given the next push token is unsigned
    When Google pushes message "m-4" of type "google.workspace.chat.message.v1.created" with data:
      """
      {}
      """
    Then the response status is 401
    And the log has entries matching:
      | event         | reason     |
      | :auth/refused | :signature |
    And the log has no entries matching:
      | level | event                 |
      | :info | :google/push-received |

  Scenario: the right claims under a signature Google never made are refused
    Given the next push token is signed by a key Google never published
    When Google pushes message "m-8" of type "google.workspace.chat.message.v1.created" with data:
      """
      {}
      """
    Then the response status is 401
    And the isaac file "google/inbox/pending/m-8.edn" does not exist
    And the log has entries matching:
      | event         | reason     |
      | :auth/refused | :signature |

  Scenario: an expired token and a token from another issuer are refused
    Given the next push token is expired
    When Google pushes message "m-9" of type "google.workspace.chat.message.v1.created" with data:
      """
      {}
      """
    Then the response status is 401
    And the log has entries matching:
      | event         | reason   |
      | :auth/refused | :expired |
    Given the next push token is issued by "https://accounts.impostor.test"
    When Google pushes message "m-10" of type "google.workspace.chat.message.v1.created" with data:
      """
      {}
      """
    Then the response status is 401
    And the log has entries matching:
      | event         | reason   |
      | :auth/refused | :unknown |
    And the isaac file "google/inbox/pending/m-10.edn" does not exist

  Scenario: Google's JWKS unreachable fails closed
    Given Google's JWKS is unreachable
    When Google pushes message "m-11" of type "google.workspace.chat.message.v1.created" with data:
      """
      {}
      """
    Then the response status is 401
    And the isaac file "google/inbox/pending/m-11.edn" does not exist
    And the log has entries matching:
      | event         | reason            |
      | :auth/refused | :jwks-unavailable |

  Scenario: a key Google rotated in is fetched once and then trusted
    Given Google's JWKS misses the kid then serves it
    When Google pushes message "m-12" of type "google.workspace.chat.message.v1.created" with data:
      """
      {"message": {"name": "spaces/AAA/messages/12"}}
      """
    Then the response status is 204
    And Google's JWKS was fetched 2 times

  Scenario: forged pushes count toward burst control like any other refused request
    When Google pushes 10 messages with forged tokens
    And Google pushes message "m-13" of type "google.workspace.chat.message.v1.created" with data:
      """
      {"message": {"name": "spaces/AAA/messages/13"}}
      """
    Then the response status is 429
    And the isaac file "google/inbox/pending/m-13.edn" does not exist

  Scenario: a Google token opens only the door
    Given a fixture route GET /fixture requires scope :fixture/read
    When Google pushes to GET /fixture
    Then the response status is 403

  Scenario: at-least-once delivery becomes exactly-once processing
    When Google pushes message "m-5" of type "google.workspace.chat.message.v1.created" with data:
      """
      {"message": {"name": "spaces/AAA/messages/5"}}
      """
    And Google pushes message "m-5" of type "google.workspace.chat.message.v1.created" with data:
      """
      {"message": {"name": "spaces/AAA/messages/5"}}
      """
    Then the response status is 204
    And the inbox holds 1 record for message "m-5"
    When the inbox worker ticks
    Then the skybeam handler received message "m-5" once

  Scenario: events route by type and a failing handler does not stop the worker
    Given the longwave fixture module handles Google events of type "gmail/watch"
    And the skybeam handler throws
    When Google pushes message "m-6" of type "google.workspace.chat.message.v1.created" with data:
      """
      {"message": {"name": "spaces/AAA/messages/6"}}
      """
    And Google pushes a Gmail watch message "m-7" with data:
      """
      {"emailAddress": "yopp@tonotop.com", "historyId": "12345"}
      """
    And the inbox worker ticks
    Then the isaac file "google/inbox/failed/m-6.edn" exists
    And the log has entries matching:
      | level  | event                  | message-id |
      | :error | :google/handler-failed | m-6        |
    And the longwave handler received message "m-7"
    And the isaac file "google/inbox/done/m-7.edn" exists
