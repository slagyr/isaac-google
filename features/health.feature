Feature: Google health and attention
  A lapsed watch or subscription goes quiet without an error. Every tick
  of the registration timer also checks health: expiry read from Google,
  how long the organization has gone without an event, whether the door
  has answered since boot, and whether the tick's own synthetic heartbeat
  came back. The tick is hourly, silence is judged per organization rather
  than per space, and every condition raises attention and a log line
  **once on the transition** — never once a tick.
  Beans: isaac-fu2m, isaac-an14.

  Background:
    Given default Grover setup in "/test/google-health"
    And config:
      | log.output                               | memory                         |
      | google.tonotop.topic                             | projects/marigold/topics/isaac |
      | google.tonotop.health.silent-after-hours         | 6                              |
      | google.tonotop.health.heartbeat.enabled          | false                          |
      | attention.notify.comm                    | discord                        |
      | attention.notify.target                  | boiler-room                    |
      | comms.gchat.gchat/account                | yopp@tonotop.com               |
      | comms.gchat.gchat/spaces.spaces/ENG.name | engineering                    |
    And the google auth store has access "at-1" and refresh "rt-1"
    And the Workspace Events API has subscription "subscriptions/s-eng" for "spaces/ENG" expiring at "2026-09-25T12:00:00Z"
    And the clock is fixed at "2026-09-18T12:00:00Z"

  Scenario: an organization past its silence threshold is reported once, not once per tick
    Given the last Google event for "spaces/ENG" was at "2026-09-18T04:00:00Z"
    When the google registration timer ticks
    Then the log has entries matching:
      | level | event          | tenant   | silent-hours |
      | :warn | :google/silent | :tonotop | 8            |
    And the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                    |
      | comm    | discord                                  |
      | target  | boiler-room                              |
      | content | contains "tonotop" and "no event for 8h" |
    When the test clock advances 3600000 milliseconds
    And the google registration timer ticks
    Then the directory "comm/delivery/pending" has exactly 1 file
    And the log has exactly 1 entries matching:
      | event          |
      | :google/silent |

  Scenario: one quiet space does not make the organization silent
    Given the Workspace Events API has subscription "subscriptions/s-ops" for "spaces/OPS" expiring at "2026-09-25T12:00:00Z"
    And the last Google event for "spaces/ENG" was at "2026-09-14T04:00:00Z"
    And the last Google event for "spaces/OPS" was at "2026-09-18T11:30:00Z"
    When the google registration timer ticks
    Then the log has no entries matching:
      | event          |
      | :google/silent |
    And the directory "comm/delivery/pending" has exactly 0 files

  Scenario: a workspace-wide registration is judged by the spaces that actually spoke
    Given the Workspace Events API has no subscriptions
    And the Workspace Events API has subscription "subscriptions/s-all" for "spaces/-" expiring at "2026-09-25T12:00:00Z"
    And the last Google event for "spaces/AAA" was at "2026-09-18T11:59:00Z"
    When the google registration timer ticks
    Then the log has no entries matching:
      | event          |
      | :google/silent |
    When the test clock advances 25200000 milliseconds
    And the google registration timer ticks
    Then the log has entries matching:
      | level | event          | tenant   | silent-hours |
      | :warn | :google/silent | :tonotop | 7            |

  Scenario: an event clears the silence and the clear is logged once
    Given the last Google event for "spaces/ENG" was at "2026-09-18T04:00:00Z"
    When the google registration timer ticks
    Then the log has entries matching:
      | event          |
      | :google/silent |
    When the test clock advances 3600000 milliseconds
    And the last Google event for "spaces/ENG" was at "2026-09-18T13:00:00Z"
    And the google registration timer ticks
    Then the log has entries matching:
      | level | event                  | kind    | subject  |
      | :info | :google/health-cleared | :silent | :tonotop |
    When the test clock advances 3600000 milliseconds
    And the google registration timer ticks
    Then the log has exactly 1 entries matching:
      | event                  |
      | :google/health-cleared |

  Scenario: a heartbeat that never arrives is reported once and cleared when one does
    Given config:
      | google.tonotop.health.heartbeat.enabled | true |
    And the last Google event for "spaces/ENG" was at "2026-09-18T11:30:00Z"
    When the google registration timer ticks
    Then a Google heartbeat was published to "projects/marigold/topics/isaac"
    And the log has no entries matching:
      | event                    |
      | :google/heartbeat-missed |
    When the test clock advances 70000 milliseconds
    And the google registration timer ticks
    Then the log has entries matching:
      | level | event                    | tenant   |
      | :warn | :google/heartbeat-missed | :tonotop |
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                     |
      | content | contains "tonotop" and "heartbeat missed" |
    When the test clock advances 70000 milliseconds
    And the google registration timer ticks
    Then the log has exactly 1 entries matching:
      | event                    |
      | :google/heartbeat-missed |
    When a Google heartbeat for "tonotop" arrived at "2026-09-18T12:03:00Z"
    And the test clock advances 70000 milliseconds
    And the google registration timer ticks
    Then the log has entries matching:
      | level | event                  | kind              | subject  |
      | :info | :google/health-cleared | :heartbeat-missed | :tonotop |

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
