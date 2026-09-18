@wip
Feature: Google user login
  isaac-google signs in as the Google user with the authorization-code
  flow and keeps the tokens in the auth store under provider "google".
  Google does not offer the device flow for Chat and Gmail scopes, so the
  login prints an authorization URL and accepts the pasted code. The token
  POST goes through the agent's HTTP client so outbound-request steps see it.
  Bean: isaac-6aw3.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | google.oauth.client-id     | isaac-test.apps.googleusercontent.com |
      | google.oauth.client-secret | shh                                   |
      | google.oauth.account       | yopp@tonotop.com                      |

  Scenario: a pasted code is exchanged and the tokens are stored
    Given the Google token endpoint returns access token "at-1" and refresh token "rt-1" expiring in 3600
    When isaac is run with "google login --code 4/0AbCd"
    Then the exit code is 0
    And the stdout contains "Signed in as yopp@tonotop.com"
    And an outbound HTTP request to "https://oauth2.googleapis.com/token" matches:
      | key               | value                                 |
      | body.grant_type   | authorization_code                    |
      | body.code         | 4/0AbCd                               |
      | body.client_id    | isaac-test.apps.googleusercontent.com |
      | body.redirect_uri | #"^http://localhost:\d+/?$"           |
    And the google auth store has access "at-1" and refresh "rt-1"

  Scenario: an expired access token is refreshed without a human
    Given the google auth store has an expired access token with refresh "rt-1"
    And the Google token endpoint returns access token "at-2" expiring in 3600
    When the google access token is resolved
    Then the google auth store has access "at-2" and refresh "rt-1"
    And an outbound HTTP request to "https://oauth2.googleapis.com/token" matches:
      | key                | value         |
      | body.grant_type    | refresh_token |
      | body.refresh_token | rt-1          |

  Scenario: the login asks for the union of contributed scopes
    Given the skybeam fixture module contributes the Google scope "https://www.googleapis.com/auth/chat.messages.readonly"
    When isaac is run with "google login"
    Then the exit code is 0
    And the stdout contains "https://accounts.google.com/o/oauth2/v2/auth"
    And the stdout contains "openid"
    And the stdout contains "chat.messages.readonly"

  Scenario: missing config fails closed
    Given Isaac root "target/test-state" has no config file
    When isaac is run with "google login"
    Then the exit code is 1
    And the stderr contains "google.oauth.client-id"

  Scenario: a dead refresh token says why
    Given the google auth store has an expired access token with refresh "rt-old"
    And the Google token endpoint rejects refresh with "invalid_grant"
    When the google access token is resolved
    Then an error is reported indicating authentication failed
    And the error mentions "consent screen"
    And the error mentions "Testing"
