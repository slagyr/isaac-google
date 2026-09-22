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
Isaac acts as · `<owner>` you.

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
DOOR=<host-url>/google/pubsub

gcloud config set project $PROJECT
gcloud services enable chat.googleapis.com workspaceevents.googleapis.com \
    pubsub.googleapis.com gmail.googleapis.com

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
```

Deliveries fail until step 5 exposes the door — expected; retention is 7 days
and the backoff keeps Pub/Sub from hammering the host.

### 2b. Console-only pieces (no gcloud surface)

- **OAuth consent screen** (`/auth/overview`): app name, support email,
  **Audience: Internal**. If one already exists in the project (another CLI's
  client, say) just confirm Internal.
- **OAuth client** (`/auth/clients` → Create): **Desktop app**, not Web. The
  module's redirect URI is `http://localhost:1/`, which only Desktop clients
  accept unregistered. Keep the id; put the secret on the host:
  `echo 'GOOGLE_CLIENT_SECRET=…' >> ~/.isaac/.env && chmod 600 ~/.isaac/.env`.
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
isaac config set google.project <project>
isaac config set google.topic projects/<project>/topics/isaac
isaac config set google.push.endpoint <host-url>/google/pubsub
isaac config set google.push.service-account isaac-push@<project>.iam.gserviceaccount.com
```

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

Open the URL **as `<account>`**, approve. The browser lands on
`http://localhost:1/?state=isaac-google&code=4/0A…` and shows "can't connect":
expected. Copy the `code=` value (URL-decode `%2F` → `/`), then
`isaac google login --code 4/0A…` → `Signed in as <account>`. A "7-day refresh
token — consent screen is still in Testing" warning means the consent screen
is not Internal; fix and log in again.

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
least one registration-tick interval (`google.tick-ms`, default 30s) to run
unassisted. Gate the bump on every check passing.

### How to run it

```sh
isaac google smoke                    # the four passive checks
isaac google smoke --tenant marigold  # one organization on a multi-tenant host
isaac google smoke --send-live        # plus one real Pub/Sub message, end to end
```

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
- **`silent`** — fails if more than `--silent-threshold` (default 0)
  `:google/silent` conditions are currently firing, reading the exact
  `isaac.google.health/evaluate` decision the live registration tick already
  makes on every pass.
- **`live-push`** (only with `--send-live`) — publishes one real message to
  the tenant's configured Pub/Sub topic (authenticated with its stored
  Google token — no stub) and polls `inbox/*` until the message arrives
  (`:pending`, `:done`, or `:failed` — arriving at all is the proof) or
  `--timeout-ms` (default 30000) elapses. Requires the token to carry
  `pubsub.topics.publish` on the topic, which the push-subscription grants
  in [§2a](#2a-script-cloud-shell-already-authenticated-as-you) do not give
  it by default — grant it to the Isaac account on the topic to use this
  flag, or run without it.

### What each FAIL means

| Check | A FAIL here means |
| --- | --- |
| `door` | The route isn't bound, isaac-http isn't loaded, or something answers something other than 401 for a bare POST. Check the process is actually running and listening on the expected port, or pass `--url` if the door isn't at `http://127.0.0.1:<http.port>/google/pubsub`. |
| `registrations` | The registration tick either hasn't run, threw, or its create/renew calls didn't land on a real, listable Google resource. Check `server.log` for `:google/registration-failed`, and confirm `google.<tenant>.topic`/`oauth` are still valid. |
| `inbox` | The inbox worker isn't scheduled or isn't draining. Confirm the server started the `:google/inbox` task (`berth/registration-summary` at boot) and that handlers aren't all crashing (check `inbox/failed`). |
| `silent` | A registered key has gone quiet past its threshold — the subscription or watch may have lapsed without erroring, or nobody is posting to the space/mailbox. Cross-check `isaac google status`'s door/last-event columns. |
| `live-push` | Either the publish call failed (commonly a permissions error — see the grant note above) or a genuinely accepted push never reached the inbox, which points at the OIDC verifier or the door handler itself rather than at Google. |

### Defect → check mapping

The six defects the first live day surfaced, and the check that would have
caught each one:

| # | Defect (2026-09-19) | Check |
| --- | --- | --- |
| 1 | `tick!` NPE — a destructured `door-up?` shadowed the fn; the scheduler calls `(tick! {})` with no opts | `registrations` and `silent` both read `health/evaluate`'s output from a **live** tick — a tick that NPE'd leaves keys unregistered / conditions stale, which `registrations`/`silent` fail on. `registration_spec.clj` and `smoke_spec.clj` also spec the production call shape, `(tick! {})`, directly. |
| 2 | `subscriptions.create` returns an Operation, not the final resource | `registrations` fails any key whose remote `:expires-at` is missing or unparsed, which is exactly what an unresolved Operation looks like. |
| 3 | `subscriptions.list` needs Google's event-type filter | `registrations` reads the live listing the same way `isaac google status` does; a list that silently returns nothing (or the wrong resources) shows up as "not registered" here. |
| 4 | The inbox worker was never scheduled in the server | `inbox` fails once `inbox/pending` grows past the threshold — direct backlog evidence, no proxy needed. |
| 5 | Chat senders have no email (`gchat`'s sender is `users/<id>`, not an address) | Out of this module's boundary (isaac-google "knows nothing of Chat or Gmail") — a handler that crashes on a sender shape it didn't expect lands its record in `inbox/failed`, which `inbox` and `live-push` both surface as evidence rather than a silent drop. The People-API email resolution itself (`isaac.google.people`) is spec'd in `people_spec.clj`; a live check of a real Chat sender belongs to `isaac.comm.gchat`'s own smoke, not this one. |
| 6 | The OIDC verifier's reflective key construction did not exist under bb | `live-push` is the only check that exercises real, Google-signed token verification end to end (`door` alone only proves the route is *bound*, not that a genuinely signed token verifies); `--send-live` is how the runtime's own OIDC path gets proven on whatever host and runtime the release actually ships on. |

### Everything the tooling above can't prove

`isaac google smoke` cannot itself send a Chat message or an email — that is
`isaac.comm.gchat`/`isaac.comm.gmail` territory, and this module is
comm-agnostic by design. The full manual round trip (login → registration →
outbound send → inbound push → turn → reply) described in [§4](#4-outbound-test)
and [§5](#5-inbound-the-door) above is still worth doing by hand once per
release on a real test space/mailbox, particularly to catch defects that
live in a *consuming* module rather than in `isaac.google` itself.
