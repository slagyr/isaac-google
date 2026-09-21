Feature: Who spoke
  Chat names a human sender users/<id> + displayName and never an email;
  Gmail names one email + name and never an id. Google is the source of
  truth for the join, so isaac-google resolves on demand through the People
  API under directory.readonly — an in-memory memo with a short TTL, nothing
  persisted. Every failure is soft: the caller keeps what it already knew.
  Bean: isaac-8s6s.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | google.tonotop.oauth.client-id     | isaac-test.apps.googleusercontent.com |
      | google.tonotop.oauth.client-secret | shh                                   |
      | google.tonotop.oauth.account       | yopp@tonotop.com                      |

  Scenario: a Chat users/<id> resolves to the Workspace name and email
    Given the Google People API knows "users/1234" as "Micah Martin" with email "micah@tonotop.com"
    When the person "users/1234" is resolved
    Then the person renders as "Micah Martin <micah@tonotop.com>"
    And the People API was asked 1 time for "users/1234" with fields "names,emailAddresses"

  Scenario: a busy thread asks Google once inside the memo TTL
    Given the Google People API knows "users/1234" as "Micah Martin" with email "micah@tonotop.com"
    When the person "users/1234" is resolved
    And the person "users/1234" is resolved
    Then the person renders as "Micah Martin <micah@tonotop.com>"
    And the People API was asked 1 time for "users/1234" with fields "names,emailAddresses"

  Scenario: without the directory scope the lookup fails soft and warns once
    Given the Google People API refuses with 403 "Request had insufficient authentication scopes."
    When the person "users/1234" is resolved with display name "Micah"
    Then the person renders as "Micah"
    And the person has no email
    When the person "users/5678" is resolved
    Then the person renders as "users/5678"
    And exactly one warning named the missing "https://www.googleapis.com/auth/directory.readonly" scope

  Scenario: the login asks for the directory scope
    When isaac is run with "google login"
    Then the exit code is 0
    And the stdout contains "directory.readonly"
