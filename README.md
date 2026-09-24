# 🍏 Isaac Google 🔑

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-google/main/isaac-google.png" alt="isaac-google" style="margin-right: 20px; margin-bottom: 10px;">

Shared Google Workspace plumbing for [Isaac](https://github.com/slagyr/isaac) —
OAuth for the Google user, the Pub/Sub push door and durable inbox,
registrations and renewal, health. Knows nothing of Chat or Gmail.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) and
[isaac-agent](https://github.com/slagyr/isaac-agent). Contributes `:isaac.google`.
Part of the Google Workspace comms epic (isaac-bv1l).

<br>

[![Google](https://github.com/slagyr/isaac-google/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-google/actions/workflows/ci-tests.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## What's here

- Module (`isaac.google.module/create-module`), manifest id `:isaac.google`.
- OAuth scopes berth, Pub/Sub handler berth, Workspace Events registration/renewal.
- Pub/Sub publishing (heartbeat, `smoke --send-live`) authenticates as a service
  account — `google.<org>.pubsub.credentials-file` — never as the signed-in user:
  a Cloud Platform scope on a human's grant drags the whole Google login under the
  Workspace's Cloud reauthentication clock (isaac-286x; see `doc/rollout.md`).
- Further work is planned in the beans under isaac-bv1l.

## Development

Sibling checkouts expected:

```
plan/
  isaac-foundation/
  isaac-agent/
  isaac-http/
  isaac-google/   # this repo
```

```sh
bb hooks:install   # once, on a fresh checkout
bb spec
bb features
bb ci
```

From the JVM:

```sh
clj -M:spec
clj -M:features
```

## Consumer coordinate

```clojure
io.github.slagyr/isaac-google {:local/root "../isaac-google"}
;; or {:git/url "https://github.com/slagyr/isaac-google.git" :git/sha "..."}
```
