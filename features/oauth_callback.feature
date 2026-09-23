Feature: The login finishes at the Isaac host
  `isaac google login` used to print a consent URL pointing at
  http://localhost:1/, where the browser dead-ended and the operator copied
  the code out of the address bar into a second command. A host that already
  publishes a Pub/Sub door publishes an OAuth callback too: the consent URL
  redirects there, this host exchanges the code itself, and the terminal that
  printed the URL prints the outcome.

  The callback carries no credentials — it is the operator's own browser — so
  what it proves, it proves with the pending login the CLI left behind: a
  state nonce names it, a PKCE verifier only that login holds is what Google
  checks, and ten minutes is all it lives.
  Bean: isaac-2abl.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | google.tonotop.oauth.client-id     | isaac-test.apps.googleusercontent.com |
      | google.tonotop.oauth.client-secret | shh                                   |
      | google.tonotop.oauth.account       | yopp@tonotop.com                      |
      | google.tonotop.push.endpoint       | https://isaac.example/google/pubsub   |
    And the Isaac server is started
    And the Google runtime component is started

  Scenario: the operator approves at Google and this host does the rest
    Given the Google token endpoint returns access token "at-1" and refresh token "rt-1" expiring in 3600
    When the operator starts "google login --tenant tonotop" and leaves it waiting
    Then the stdout eventually contains "https://accounts.google.com/o/oauth2/v2/auth"
    And the consent URL sends the browser back to "https://isaac.example/google/oauth/callback"
    And the consent URL carries a PKCE challenge
    And a pending login is recorded for organization "tonotop"
    When Google redirects to the callback with the recorded state and code "4/0AbCd"
    Then the response status is 200
    And the response body contains "Signed in as yopp@tonotop.com for organization tonotop. You can close this tab."
    And the google auth store for tenant "tonotop" has access "at-1" and refresh "rt-1"
    And an outbound HTTP request to "https://oauth2.googleapis.com/token" matches:
      | key               | value                                       |
      | body.grant_type   | authorization_code                          |
      | body.code         | 4/0AbCd                                     |
      | body.redirect_uri | https://isaac.example/google/oauth/callback |
    And the log has entries matching:
      | level | event                   | tenant  |
      | :info | :google/login-completed | tonotop |
    And the stdout eventually contains "Signed in for organization tonotop"
    And the exit code is 0

  Scenario: a state this host is not holding a login under opens nothing
    Given a login for organization "tonotop" under state "st-coldconsent" started 11 minutes ago
    When Google redirects to the callback with state "st-nosuchstateatall" and code "4/0AbCd"
    Then the response status is 400
    And the response body contains "unknown to this Isaac"
    When Google redirects to the callback with state "st-coldconsent" and code "4/0AbCd"
    Then the response status is 410
    And the response body contains "expired"
    And no outbound HTTP request to "https://oauth2.googleapis.com/token" was made
