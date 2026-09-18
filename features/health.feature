@wip
Feature: Google health and attention
  A lapsed watch or subscription goes quiet without an error. Every tick
  of the registration timer also checks health: expiry read from Google,
  time since the last event, and whether the door has answered since
  boot. Failures raise attention through the comm outbox, once per
  condition until it clears, and are retried at once. Bean: isaac-fu2m.

  Background:
    Given default Grover setup in "/test/google-health"
    And config:
      | log.output                               | memory                         |
      | google.topic                             | projects/marigold/topics/isaac |
      | google.health.silent-after-hours         | 6                              |
      | attention.notify.comm                    | discord                        |
      | attention.notify.target                  | boiler-room                    |
      | comms.gchat.gchat/account                | yopp@tonotop.com               |
      | comms.gchat.gchat/spaces.spaces/ENG.name | engineering                    |
    And the google auth store has access "at-1" and refresh "rt-1"
    And the Workspace Events API has subscription "subscriptions/s-eng" for "spaces/ENG" expiring at "2026-09-25T12:00:00Z"
    And the clock is fixed at "2026-09-18T12:00:00Z"

  Scenario: silence past the threshold raises attention once, not once per tick
    Given the last Google event for "spaces/ENG" was at "2026-09-18T04:00:00Z"
    When the google registration timer ticks
    Then the log has entries matching:
      | level | event          | key        | silent-hours |
      | :warn | :google/silent | spaces/ENG | 8            |
    And the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                       |
      | comm    | discord                                     |
      | target  | boiler-room                                 |
      | content | contains "spaces/ENG" and "no event for 8h" |
    When the test clock advances 3600000 milliseconds
    And the google registration timer ticks
    Then the directory "comm/delivery/pending" has exactly 1 file

  Scenario: an expiry in the past is renewed immediately and reported
    Given the Workspace Events API has subscription "subscriptions/s-eng" for "spaces/ENG" expiring at "2026-09-18T09:00:00Z"
    And the Workspace Events API grants subscriptions expiring at "2026-09-25T12:00:00Z"
    And the last Google event for "spaces/ENG" was at "2026-09-18T11:30:00Z"
    When the google registration timer ticks
    Then the log has entries matching:
      | level | event           | key        |
      | :warn | :google/expired | spaces/ENG |
      | :info | :google/renewed | spaces/ENG |
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                               |
      | content | contains "spaces/ENG" and "expired" |

  Scenario: a door nobody has reached is reported as exposure, not Google
    Given the Isaac server is started
    And the last Google event for "spaces/ENG" was at "2026-09-18T04:00:00Z"
    When the google registration timer ticks
    Then the log has entries matching:
      | level | event                  |
      | :warn | :google/door-unreached |
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                                     |
      | content | contains "door has answered nothing since boot" and "Funnel" |

  Scenario: isaac google status shows registrations, expiry, last event and the door
    Given the last Google event for "spaces/ENG" was at "2026-09-18T11:30:00Z"
    When isaac is run with "google status"
    Then the exit code is 0
    And the stdout contains "spaces/ENG"
    And the stdout contains "2026-09-25T12:00:00Z"
    And the stdout contains "2026-09-18T11:30:00Z"
    And the stdout contains "door: never"
