Feature: The heartbeat is published from outside Isaac
  Isaac publishes nothing to Pub/Sub. A Cloud Scheduler job publishes the
  heartbeat to the organization's own topic as a Google APIs service account
  *inside* GCP: the identity is never exported, so there is no key for
  `constraints/iam.disableServiceAccountKeyCreation` to refuse, and the
  message originates outside the process being tested rather than in a loop
  Isaac controls at both ends.
  Isaac's half is watching, and it is judged by arrival recency against the
  interval the organization says to expect one on. That interval is what turns
  the watch on — nothing else could say what late means — so a heartbeat
  configured without one is a config error at load, not a watchdog that reads
  as enabled and cannot bark.
  Beans: isaac-clly (replacing isaac-286x's publishing identity).

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: an organization that names its interval is asked for nothing more
    Given config:
      | google.tonotop.topic                                 | projects/marigold/topics/isaac |
      | google.tonotop.health.heartbeat.expected-interval-ms | 3600000                        |
    When isaac is run with "config validate"
    Then the exit code is 0

  # Leftover or half-finished heartbeat config is a warning, not a refusal.
  # The host still receives; it simply watches for nothing, and is told so.
  # Refusing here would stop a working host over config that changes nothing.
  Scenario: a heartbeat configured with no interval warns and still validates
    Given config:
      | google.tonotop.topic                     | projects/marigold/topics/isaac |
      | google.tonotop.health.heartbeat.grace-ms | 60000                          |
    When isaac is run with "config validate"
    Then the exit code is 0
    And the stderr matches:
      | pattern                                                    |
      | google\.tonotop\.health\.heartbeat\.expected-interval-ms   |
      | no interval                                                |

  Scenario: an interval that could never be late is a config error
    Given config:
      | google.tonotop.health.heartbeat.expected-interval-ms | 0 |
    When isaac is run with "config validate"
    Then the exit code is 1
    And the stderr matches:
      | pattern  |
      | positive |

  # An operator who had the heartbeat switched on is told what replaced the
  # switch — but is not stopped. yopp ran health.heartbeat.enabled false for a
  # day; upgrading onto a build that refused it would have taken the host down
  # over a key that now changes nothing.
  Scenario: the retired enabled switch warns and names its replacement
    Given config:
      | google.tonotop.health.heartbeat.enabled | false |
    When isaac is run with "config validate"
    Then the exit code is 0
    And the stderr matches:
      | pattern              |
      | retired              |
      | expected-interval-ms |

  # isaac-286x demanded a service-account JSON key of every organization with
  # a topic whose heartbeat was on. Nothing here publishes now, so nothing
  # here wants a credential — and the org policy that forbids issuing one
  # stops being a reason to run without a silence detector.
  Scenario: an organization with a topic is asked for no publishing credential
    Given config:
      | google.tonotop.topic | projects/marigold/topics/isaac |
    When isaac is run with "config validate"
    Then the exit code is 0
    And the stderr does not contain "credentials-file"
