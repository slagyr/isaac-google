@wip
Feature: Google Pub/Sub push door
  Google pushes every subscription's events to one Isaac door. The door
  is an identity source under per-principal auth (isaac-gym1): a valid
  Google OIDC token for the door's audience is principal google-pubsub
  with scope :google/push and nothing more. Accepted events are persisted
  and answered 204 before any handler runs; an inbox worker hands them to
  the handler a module contributed for the event type. Bean: isaac-1jep.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | google.topic                | projects/marigold/topics/isaac               |
      | google.push.endpoint        | https://isaac.example/google/pubsub          |
      | google.push.service-account | pubsub-push@marigold.iam.gserviceaccount.com |
    And Google signs push tokens with a test key
    And the skybeam fixture module handles Google events of type "google.workspace.chat.message.v1.created"
    And the Isaac server is started

  Scenario: a valid push is persisted and acknowledged before any handler runs
    When Google pushes message "m-1" of type "google.workspace.chat.message.v1.created" with data:
      """
      {"message": {"name": "spaces/AAA/messages/1"}}
      """
    Then the response status is 204
    And the isaac file "google/inbox/pending/m-1.edn" exists
    And the skybeam handler received no messages
    And the log has entries matching:
      | level | event                 | principal     | message-id |
      | :info | :google/push-received | google-pubsub | m-1        |
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
    Given the next push token is from "someone-else@marigold.iam.gserviceaccount.com"
    When Google pushes message "m-3" of type "google.workspace.chat.message.v1.created" with data:
      """
      {}
      """
    Then the response status is 401
    Given the next push token is unsigned
    When Google pushes message "m-4" of type "google.workspace.chat.message.v1.created" with data:
      """
      {}
      """
    Then the response status is 401
    And the log has no entries matching:
      | level | event                 |
      | :info | :google/push-received |

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
