# Rolling out Google Workspace comms on an Isaac host

How to take a host from "no Google" to Chat + Gmail on Isaac's Google modules.
Host-specific values (project id, hostnames, accounts) belong in your own
operations notes, not here; this document uses placeholders.

Modules: `isaac.google` (OAuth, push door, inbox, registration timer),
`isaac.comm.gchat`, `isaac.comm.gmail`. Requires `isaac.http` ≥ 0.1.20 (OIDC
verifier with config-ref trust rules) — the door must never be exposed on an
older http, which accepted unsigned tokens.

Placeholders: `<project>` GCP project id · `<org-id>` organization id ·
`<host-url>` the server's public HTTPS origin · `<account>` the Google account
Isaac acts as · `<owner>` you · `<region>` a Cloud Scheduler location
(`gcloud scheduler locations list`).

## 1. Isaac side

1. Registry entries for the three modules (and http if it needs bumping).
2. On the host: `isaac modules upgrade isaac.http` if < 0.1.20;
   `isaac modules install isaac.google`, `… isaac.comm.gchat`,
   `… isaac.comm.gmail`; gate on `isaac modules list` all `ok`; restart;
   confirm `server/started` and a `berth/registration-summary` with
   `:isaac.http/identity 1` and `:isaac.google/registration 2`.
3. `isaac config validate`.
4. Smoke the door before exposing anything:
   `curl -X POST http://127.0.0.1:<port>/google/pubsub -d {}` → 401.

## 2. GCP side

One Google Cloud project per Isaac host, **created under the Workspace
organization**, not a personal account: that makes the consent screen
*Internal* (no verification review, no 7-day refresh-token expiry) and lets
Chat/Workspace Events see the Workspace. Another Isaac host gets its own
project and topic.

### 2a. Script (Cloud Shell, already authenticated as you)

```bash
PROJECT=<project>
ORG=<org-id>                 # gcloud organizations list
REGION=<region>              # gcloud scheduler locations list
DOOR=<host-url>/google/pubsub

gcloud config set project $PROJECT
gcloud services enable chat.googleapis.com workspaceevents.googleapis.com \
    pubsub.googleapis.com gmail.googleapis.com cloudscheduler.googleapis.com

# If the org enforces Domain Restricted Sharing it blocks Google's own push
# accounts. Override it on THIS project only. Needs Organization Policy
# Administrator at the ORG — Organization Administrator + project Owner are
# not enough, and the role only appears in the console picker when the org is
# the selected resource.
gcloud organizations add-iam-policy-binding $ORG \
    --member=user:<owner> --role=roles/orgpolicy.policyAdmin
cat > /tmp/drs.yaml <<EOF
name: projects/$PROJECT/policies/iam.allowedPolicyMemberDomains
spec:
  inheritFromParent: false
  rules:
  - allowAll: true
EOF
gcloud org-policies set-policy /tmp/drs.yaml
# Leave the override in place: restoring it can make later edits to the
# topic's IAM policy fail validation.

gcloud pubsub topics create isaac
for sa in chat-api-push gmail-api-push; do
  gcloud pubsub topics add-iam-policy-binding isaac --role=roles/pubsub.publisher \
      --member=serviceAccount:$sa@system.gserviceaccount.com
done

# The identity Pub/Sub signs push tokens with. No roles.
gcloud iam service-accounts create isaac-push --display-name=isaac-push
gcloud pubsub subscriptions create isaac-push --topic=isaac \
    --push-endpoint=$DOOR \
    --push-auth-service-account=isaac-push@$PROJECT.iam.gserviceaccount.com \
    --push-auth-token-audience=$DOOR \
    --min-retry-delay=10s --max-retry-delay=600s --expiration-period=never

# The heartbeat. Google publishes it, not Isaac: a Cloud Scheduler job puts
# one marked message on the same topic on a fixed schedule, Pub/Sub pushes it
# to the door like any other event, and Isaac watches for its arrival. There
# is no user-managed service account here and no key — see "Why Google
# publishes the heartbeat" below.
gcloud scheduler jobs create pubsub isaac-heartbeat \
    --location=$REGION \
    --schedule="*/5 * * * *" \
    --time-zone=Etc/UTC \
    --topic=projects/$PROJECT/topics/isaac \
    --message-body='{"isaac-heartbeat":true,"tenant":"<org-id>"}' \
    --attributes=ce-type=isaac.google/heartbeat
```

### 2a-i. The Cloud Scheduler heartbeat, in detail

**The message.** The door matches a heartbeat two ways and the job sets both,
so a subscription that drops attributes still tells the door what it is
holding:

- the Pub/Sub attribute `ce-type` = `isaac.google/heartbeat`, and
- `"isaac-heartbeat": true` in the JSON body.

Either alone is enough. The `tenant` field in the body is for a human reading
the message in the console — Isaac ignores it. Which organization a push
belongs to is settled the way it is for every other push: the project in the
subscription name and the service account whose OIDC token the request
proved. A publisher cannot name a tenant it has not proved.

**The schedule must match the configured interval.** `--schedule` is
unix-cron in `--time-zone`; whatever it says, tell Isaac the same thing in
milliseconds:

| `--schedule` | `expected-interval-ms` |
| --- | --- |
| `*/5 * * * *` (every 5 min) | `300000` |
| `*/15 * * * *` | `900000` |
| `0 * * * *` (hourly) | `3600000` |

Isaac reports `:heartbeat-missed` once nothing has arrived for
`expected-interval-ms` + `grace-ms` (default 60000). Give the grace room for
scheduler jitter and delivery; a five-minute schedule with a one-minute grace
is comfortable. Configure the interval *larger* than the job's period, never
smaller: a job that runs hourly against a five-minute interval alarms forever.

**IAM.** A Pub/Sub target needs no user-managed service account: Cloud
Scheduler publishes to the topic as a Google APIs service account, and a
user-managed one is required only for **HTTP** targets. For a topic in the
same project the Cloud Scheduler service agent
(`service-<project-number>@gcp-sa-cloudscheduler.iam.gserviceaccount.com`) is
granted `roles/cloudscheduler.serviceAgent` when the API is enabled and needs
nothing more. If publishes are denied — or the topic lives in another project
— grant the publisher role explicitly:

```bash
NUM=$(gcloud projects describe $PROJECT --format='value(projectNumber)')
gcloud pubsub topics add-iam-policy-binding isaac --role=roles/pubsub.publisher \
    --member=serviceAccount:service-$NUM@gcp-sa-cloudscheduler.iam.gserviceaccount.com
```

**Prove it before trusting it**, once the door is up (§5):

```bash
gcloud scheduler jobs run isaac-heartbeat --location=$REGION
gcloud scheduler jobs describe isaac-heartbeat --location=$REGION   # lastAttemptTime, state
```

`server.log` should show `:google/heartbeat-received` within seconds. Nothing
lands in the inbox and nothing starts a turn — a heartbeat is not an event.

### Why Google publishes the heartbeat (isaac-286x, isaac-clly)

`https://www.googleapis.com/auth/pubsub` is a Google **Cloud Platform** scope.
A Workspace that configures *Admin console → Security → Access and data
control → Google session control → Google Cloud console and SDK session
control* applies its reauthentication frequency — default **16 hours** — to
every app requiring a Cloud Platform scope, and the page says in as many words
that this "also applies to non-Google apps". While isaac-google asked for
`auth/pubsub` on the **user** login, that one machine scope put the whole
grant — Gmail, Chat, directory — under the clock: on yopp the refresh token
died 14h50m after every login, survived a fresh login, and died again on the
same cycle, with the stored refresh token byte-identical throughout because
Google revoked it server-side (isaac-ey6q).

isaac-286x moved publishing to a service account of Isaac's own. That works
only where a key can be issued, and this organization forbids exactly that:

    FAILED_PRECONDITION: Key creation is not allowed on this service account
    constraints/iam.disableServiceAccountKeyCreation

which is a policy worth keeping — long-lived service-account keys are a
standard breach vector — and which left yopp with the heartbeat switched off
and the push pipeline with no silence detector at all.

So Isaac publishes nothing. Google's own documentation on a Cloud Scheduler
job with a Pub/Sub target states that "Cloud Scheduler will publish messages
to this topic as a Google APIs service account"; a user-managed service
account is required only for HTTP targets. That identity lives inside GCP and
is never exported, so there is no key to create, none to steal, and nothing
for the org policy to refuse. The `isaac-pubsub` service account created on
2026-09-24 is inert and can be deleted along with its `roles/pubsub.publisher`
binding.

It is also a better probe than Isaac publishing to itself. The message
originates outside the process being tested, on its own timer, so it exercises
the path a real event takes instead of a loop Isaac controls at both ends —
and a registration tick that never runs can no longer hide by failing to
publish the very heartbeat that would have reported it.

Everything else stays on the human's token on purpose: a Workspace Events
subscription on `spaces/-` means "every space *this account* belongs to",
which is a statement about a person, and `subscriptions.create` needs no
Pub/Sub scope to name a topic as its notification endpoint.

Deliveries fail until step 5 exposes the door — expected; retention is 7 days
and the backoff keeps Pub/Sub from hammering the host.

### 2b. Console-only pieces (no gcloud surface)

- **OAuth consent screen** (`/auth/overview`): app name, support email,
  **Audience: Internal**. If one already exists in the project (another CLI's
  client, say) just confirm Internal.
- **OAuth client** (`/auth/clients` → Create). Keep the id; put the secret on
  the host: `echo 'GOOGLE_CLIENT_SECRET=…' >> ~/.isaac/.env && chmod 600
  ~/.isaac/.env`. Which type depends on how the login ends:
  - **Web application** — for the login that finishes at this host, which is
    what a host with a public door should use. Under **Authorized redirect
    URIs** add exactly `https://<host>/google/oauth/callback`. Google refuses
    the consent with `redirect_uri_mismatch` if it is missing or differs by so
    much as a trailing slash, and only Web clients have that field at all.
  - **Desktop app** — for a host the internet cannot reach. Its redirect URI
    is `http://localhost:1/`, which Desktop clients accept unregistered, and
    the operator pastes the code with `--code` (below).
- **Chat app configuration** (Chat API → Configuration): required by Google
  even though Isaac never acts as a bot. Name/avatar/description; **turn
  interactive features OFF** (connection settings / triggers / commands then
  go dead — a placeholder URL is fine if the form insists); visibility can be
  unchecked or just yourself; "Log errors to Logging" on.

Coexisting with another Google CLI on the same host: separate OAuth client,
separate refresh token, separate credential store — no conflict. Scopes are
per grant; Isaac asks for its own.

## 3. Configure and log in (host shell; the secret never transits chat)

Non-secret keys first:

```
isaac config set google.<org>.project <project>
isaac config set google.<org>.topic projects/<project>/topics/isaac
isaac config set google.<org>.push.endpoint <host-url>/google/pubsub
isaac config set google.<org>.push.service-account isaac-push@<project>.iam.gserviceaccount.com
isaac config set google.<org>.health.heartbeat.expected-interval-ms 300000
```

`health.heartbeat.expected-interval-ms` is the Cloud Scheduler schedule from
step 2a, in milliseconds. Setting it is what turns the heartbeat watch on;
there is no separate switch, because a switch that could be on over an
interval nobody set is a watchdog that reads as enabled and cannot bark.
Leaving it unset is a real choice — the push pipeline simply loses its silence
detector — but half-configuring it is not: a `health.heartbeat` block with no
interval fails `isaac config validate` and the host refuses to start.

No publishing credential is configured anywhere. Isaac publishes nothing.

**Upgrading a host that already runs isaac-google.** Unlike isaac-286x's
upgrade, the config change cannot go in first and cannot go in last. Each
build rejects what the other requires:

- on the **old** build, unsetting `health.heartbeat.enabled` turns the
  heartbeat back on by default, which demands
  `pubsub.credentials-file` — the key the org policy refuses to issue;
- on the **new** build, `health.heartbeat.enabled` is **retired**, and a
  retired key is a hard error, not a warning: the host refuses to start.

So the module swap and the config edit happen in the same window, with **one
restart after both**. Do not restart in between.

1. create the Cloud Scheduler job (step 2a) and prove it publishes —
   `gcloud scheduler jobs run isaac-heartbeat --location=<region>` — with the
   old build still running. Arrivals are recorded and judged by nothing yet,
   which is harmless;
2. `isaac config set google.<org>.health.heartbeat.expected-interval-ms 300000`
   on the old build. It is an unknown key there, which is a warning, so this
   costs nothing and leaves the interval already in place;
3. `isaac modules upgrade isaac.google`, then, **before restarting**:

   ```
   isaac config unset google.<org>.health.heartbeat.enabled
   isaac config unset google.<org>.pubsub.credentials-file
   isaac config validate     # must be clean before the restart
   ```

4. restart;
5. delete what no longer publishes: `gcloud iam service-accounts delete
   isaac-pubsub@<project>.iam.gserviceaccount.com` (its
   `roles/pubsub.publisher` binding on the topic goes with it), and
   `rm ~/.isaac/google/pubsub-sa.json` on the host. Nothing reads either one;
6. watch `server.log` for `:google/heartbeat-received`, and for no
   `:google/heartbeat-missed` once an interval has passed.

**yopp today** runs `google.tonotop.health.heartbeat.enabled false` and
publishes nothing, which is why its push pipeline has no silence detector.
That is the config this upgrade replaces: unset `enabled`, create the
Scheduler job, and set `expected-interval-ms` to its schedule.

Restart if the host does not hot-reload; `isaac http auth list` then shows
`google-pubsub (oidc)` with the issuer and the resolved audience.

Then, as the owner on the host:

```
isaac config set google.oauth.client-id <id>.apps.googleusercontent.com
isaac config set google.oauth.client-secret '${GOOGLE_CLIENT_SECRET}'
isaac config set google.oauth.account <account>
isaac config validate
isaac google login
```

Open the URL **as `<account>`** and approve. Where the browser lands depends
on whether this host publishes a door:

- **With a push endpoint (or an explicit redirect base).** The consent screen
  redirects to `https://<host>/google/oauth/callback`, this host exchanges the
  code itself, and the browser shows "Signed in as `<account>` for
  organization `<org>`. You can close this tab." The `isaac google login` you
  left running prints `Signed in for organization <org>` and exits — there is
  no code to copy. The callback URI is derived from
  `google.<org>.push.endpoint` by dropping its path; set
  `google.<org>.oauth.redirect-base` (`https://<host>`, no path) when the
  callback is served on a different public name.
- **With neither.** The browser lands on
  `http://localhost:1/?state=isaac-google&code=4/0A…` and shows "can't
  connect": expected. Copy the `code=` value (URL-decode `%2F` → `/`), then
  `isaac google login --code 4/0A…` → `Signed in as <account>`.

The consent screen is good for ten minutes; after that the callback answers
"That sign-in expired" and the command says `Login timed out — run again, or
use --code`. A "7-day refresh token — consent screen is still in Testing"
warning means the consent screen is not Internal; fix and log in again.

Scopes widen when a comm is added (gchat: chat.messages, chat.spaces.readonly;
gmail: gmail.readonly, gmail.send). Configure the comms you want **before** the
login, or log in again after adding one.

## 4. Outbound test

```
isaac config set comms.gchat.type gchat
isaac config set comms.gchat.gchat/account <account>
isaac config set comms.gchat.gchat/allow-from <owner>           # fail-closed if empty
isaac config set comms.gchat.gchat/spaces.spaces/<id>.name test  # space id from the Chat URL
isaac config set comms.gchat.gchat/spaces.spaces/<id>.crew <crew>
isaac config set comms.gmail.type gmail
isaac config set comms.gmail.gmail/account <account>
isaac config set comms.gmail.gmail/allow-from <owner>
isaac config set comms.gmail.gmail/crew <crew>
isaac config validate    # then restart
isaac google status
```

On the first registration tick the timer creates the Workspace Events
subscription for each space and the Gmail INBOX watch (`status` lists both
with expiries; `:google/registered` in server.log). Then have a session send a
message to the space with the `comm-send` tool; it appears in Chat as
`<account>`.

## 5. Inbound (the door)

Hard gate: isaac.google ≥ 0.1.3 and isaac.http ≥ 0.1.20 (real OIDC
verification). Expose `<host-url>` to the internet (Tailscale Funnel, a load
balancer, a tunnel — whatever the host's posture allows). Auth is scopes;
Google's token opens only `/google/pubsub`. @-mention the account in the
space → `:google/push-received` → turn → reply in thread. Send the mailbox an
email for Gmail.

If the host must not have a public surface, the alternative is a pull
consumer draining the same topic outbound-only; the topic, grants, and OAuth
are identical and a pull subscription replaces the push one in one call. The
modules are push-first today; a pull consumer is not yet written.

## Smoke before shipping

2026-09-19's first live day found six defects that every green suite had
missed, because the suites drove timers by hand (`(tick! {:now ...})`, never
what the scheduler actually calls) and stubbed Google's API from docs rather
than calling it. `isaac google smoke` re-checks what the server's own
scheduler and Google's own API actually did, against an **already-running
server on this host** — it starts nothing and drives no timer itself.

### When to run it

Before pinning a version bump of `isaac.google`, `isaac.comm.gchat`, or
`isaac.comm.gmail` on a live host (whichever host is carrying the
release). Run it after the new modules are installed and the host has had at
least one registration tick (hourly) to run unassisted — the tick is what
registers, renews and judges health — and at least one Cloud Scheduler
heartbeat has had time to arrive. Gate the bump on every check passing.

### How to run it

```sh
isaac google smoke                    # the four checks
isaac google smoke --tenant marigold  # one organization on a multi-tenant host
```

`--send-live` is **retired**. It published one real message to the topic to
prove the door, the OIDC verifier and `inbox/accept!` end to end; publishing
is no longer Isaac's job, and the Cloud Scheduler heartbeat is that same proof
run every interval instead of when somebody remembers. The `silent` check
below reads it. Passed anyway, the flag prints a line saying so and changes
nothing.

`isaac google smoke` ships with the module (a CLI subcommand, not a `bb`
task) so it runs anywhere `isaac.google` is installed — a production host
gets the compiled module via `isaac modules install`, not a dev checkout
with a `bb.edn`. That matches `isaac google status`, which already assembles
most of the same evidence (live Workspace Events listing, the Gmail watch's
persisted expiry, health state); `smoke` reuses that assembly and turns it
into pass/fail instead of a status table.

Each check prints one line, `PASS <check> — <evidence>` or `FAIL <check> —
<evidence>`, and the command exits non-zero if any check fails:

- **`door`** — POSTs an empty, unauthenticated body at `/google/pubsub` and
  expects **401**. Proves the route is bound and isaac-http's identity layer
  is loaded and answering, without touching inbox state (isaac-http refuses
  the request before `isaac.google.http/handler` ever sees it).
- **`registrations`** (one line per configured tenant on a multi-tenant
  host) — for every configured key (a Chat space subscription or the Gmail
  watch), reads the **live** Workspace Events listing merged with the Gmail
  watch's own persisted expiry — the same real-Google read `isaac google
  status` does — and fails if any key is unregistered, expired, or expiring
  inside the renew window.
- **`inbox`** — fails if `inbox/pending` holds more records than
  `--inbox-threshold` (default 0). A scheduled, running worker drains it
  continuously; a worker that was never scheduled leaves it to grow forever.
- **`silent`** — fails if more than `--silent-threshold` (default 0) silence
  conditions are currently firing, reading the exact
  `isaac.google.health/evaluate` decision the live registration tick already
  makes on every pass. Two conditions count: an organization with no event
  from Google inside its `silent-after-hours` (judged **per organization**,
  not per space — a quiet space is normal — over every space that has ever
  spoken plus its own heartbeat, not over the keys it happens to register),
  and a heartbeat that has not arrived inside
  `expected-interval-ms` + `grace-ms`.

### Health is a heartbeat (isaac-an14, isaac-clly)

The failure mode of this pipeline is silence: an expired subscription, a
broken Pub/Sub binding and a dead Funnel all look exactly like nobody
talking. So a real message goes onto the organization's own topic on a
schedule, delivered by Google to the real door and verified like any other
push. Isaac does not publish it — the Cloud Scheduler job of
[§2a-i](#2a-i-the-cloud-scheduler-heartbeat-in-detail) does — and Isaac's half
is watching for its arrival.

```
google.<organization>.health.silent-after-hours                6      ; hours with no event at all
google.<organization>.health.heartbeat.expected-interval-ms           ; no default — see below
google.<organization>.health.heartbeat.grace-ms                60000  ; default
```

- **Naming the interval is what turns the watch on.** It is also the only
  thing that can say what late means, so there is no separate on/off switch.
  `health.heartbeat.enabled` is retired, and a `health.heartbeat` block with
  no interval fails `isaac config validate`: the one configuration worth
  refusing is the one that reads as watched and can never fire.
- The condition is **arrival recency**, not a send paired with an arrival.
  Nothing arriving for `expected-interval-ms` + `grace-ms` is
  `:heartbeat-missed`; the next arrival clears it. An organization that has
  **never** seen a heartbeat reports missed too — silence from the start is
  the same pipeline failure as silence after a year, and a topic that was
  never wired up is exactly the case a first-arrival grace period would hide
  forever. (A host that has only just booted is reported as `door-unreached`
  instead, which suppresses the rest.)
- A heartbeat is recorded at the door as a heartbeat and nothing else: it is
  never persisted to the inbox, never reaches a handler, never starts a turn,
  and never appears in `isaac google status`'s last-event column — nobody
  spoke. It does count as having *heard from* the organization, so an
  organization whose heartbeats keep arriving is not reported silent just
  because the humans are quiet; silence then means the pipeline delivered
  nothing at all.
- Which organization a heartbeat belongs to comes from the subscription's
  project and the OIDC principal, never from the body. An outside publisher
  cannot claim a tenant it has not proved.
- Every condition — silence, expiry, a missed heartbeat, an unreached door —
  raises attention and logs **once on the transition**. `:google/silent` and
  `:google/heartbeat-missed` fire when the condition appears;
  `:google/health-cleared` when it goes. A condition that is merely still
  present says nothing, so `server.log` shows at most one line per transition
  per organization, not one per tick.

### What each FAIL means

| Check | A FAIL here means |
| --- | --- |
| `door` | The route isn't bound, isaac-http isn't loaded, or something answers something other than 401 for a bare POST. Check the process is actually running and listening on the expected port, or pass `--url` if the door isn't at `http://127.0.0.1:<http.port>/google/pubsub`. |
| `registrations` | The registration tick either hasn't run, threw, or its create/renew calls didn't land on a real, listable Google resource. Check `server.log` for `:google/registration-failed`, and confirm `google.<tenant>.topic`/`oauth` are still valid. |
| `inbox` | The inbox worker isn't scheduled or isn't draining. Confirm the server started the `:google/inbox` task (`berth/registration-summary` at boot) and that handlers aren't all crashing (check `inbox/failed`). |
| `silent` | Either this organization has seen no event at all past its threshold (subscription or watch lapsed without erroring, or genuinely nobody is talking), or its heartbeat has not arrived inside `expected-interval-ms` + `grace-ms` — which is the pipeline itself: the Cloud Scheduler job, the topic, the subscription, Funnel, OIDC verification or the door. Check `gcloud scheduler jobs describe isaac-heartbeat --location=<region>` first (state, `lastAttemptTime`), then `isaac google status`'s door column and `server.log` for `:google/heartbeat-received`. A heartbeat missed on a host that has *never* seen one usually means the job, the attribute or the topic is wrong rather than that delivery broke. |

### Defect → check mapping

The six defects the first live day surfaced, and the check that would have
caught each one:

| # | Defect (2026-09-19) | Check |
| --- | --- | --- |
| 1 | `tick!` NPE — a destructured `door-up?` shadowed the fn; the scheduler calls `(tick! {})` with no opts | `registrations` and `silent` both read `health/evaluate`'s output from a **live** tick — a tick that NPE'd leaves keys unregistered / conditions stale, which `registrations`/`silent` fail on. `registration_spec.clj` and `smoke_spec.clj` also spec the production call shape, `(tick! {})`, directly. |
| 2 | `subscriptions.create` returns an Operation, not the final resource | `registrations` fails any key whose remote `:expires-at` is missing or unparsed, which is exactly what an unresolved Operation looks like. |
| 3 | `subscriptions.list` needs Google's event-type filter | `registrations` reads the live listing the same way `isaac google status` does; a list that silently returns nothing (or the wrong resources) shows up as "not registered" here. |
| 4 | The inbox worker was never scheduled in the server | `inbox` fails once `inbox/pending` grows past the threshold — direct backlog evidence, no proxy needed. |
| 5 | Chat senders have no email (`gchat`'s sender is `users/<id>`, not an address) | Out of this module's boundary (isaac-google "knows nothing of Chat or Gmail") — a handler that crashes on a sender shape it didn't expect lands its record in `inbox/failed`, which `inbox` surfaces as evidence rather than a silent drop. The People-API email resolution itself (`isaac.google.people`) is spec'd in `people_spec.clj`; a live check of a real Chat sender belongs to `isaac.comm.gchat`'s own smoke, not this one. |
| 6 | The OIDC verifier's reflective key construction did not exist under bb | `silent`. The Cloud Scheduler heartbeat is a real, Google-signed push through the real door: it verifies or it does not arrive, and not arriving is `:heartbeat-missed`. `door` alone only proves the route is *bound*, not that a genuinely signed token verifies. This used to be `--send-live`'s job, run by hand; it now runs every interval on whatever host and runtime the release ships on. |

### Everything the tooling above can't prove

`isaac google smoke` cannot itself send a Chat message or an email — that is
`isaac.comm.gchat`/`isaac.comm.gmail` territory, and this module is
comm-agnostic by design. The full manual round trip (login → registration →
outbound send → inbound push → turn → reply) described in [§4](#4-outbound-test)
and [§5](#5-inbound-the-door) above is still worth doing by hand once per
release on a real test space/mailbox, particularly to catch defects that
live in a *consuming* module rather than in `isaac.google` itself.
