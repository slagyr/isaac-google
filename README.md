# 🍏 isaac-google

Shared Google Workspace plumbing for Isaac — OAuth for the Google user, the Pub/Sub push door and durable inbox, registrations and renewal, health. Knows nothing of Chat or Gmail.

Part of the Google Workspace comms epic (isaac-bv1l). Depends on
[isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent).

[![CI](https://github.com/slagyr/isaac-google/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-google/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)

## What's here

- Module skeleton (`isaac.google.module/create-module`), manifest id `:isaac.google`.
- Everything else is planned in the beans under isaac-bv1l.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-google/   # this repo
```

```sh
bb hooks:install   # once, on a fresh checkout
bb spec
bb ci
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-google {:local/root "../isaac-google"}
;; or {:git/url "https://github.com/slagyr/isaac-google.git" :git/sha "..."}
```
