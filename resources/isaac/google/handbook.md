# isaac.google — Google Workspace plumbing

You are a crew running inside Isaac. This chapter covers **isaac-google**:
the module that signs Isaac in as a Google user, owns the Pub/Sub push door
and the durable inbox behind it, keeps Google registrations renewed, watches
organization health, and answers "who is this person?" through the Workspace
directory. Read `isaac.foundation` first if you haven't — this chapter
assumes its vocabulary (config paths, `handbook__configure`, hot reload) and
its Files/Config sections. Crew and tool-allowlist mechanics referenced below
belong to `isaac.agent`.

isaac-google **knows nothing of Chat or Gmail**. It hands each Pub/Sub event
to whichever module registered a handler for its type, and lends its token
to whichever module needs one — Chat behavior lives in `isaac.comm.gchat`,
Gmail's in `isaac.comm.gmail`. This chapter only covers the plumbing every
Google-backed comm rides on.

Every example below uses the placeholder organization id `acme` — substitute
whatever id your own `:google` table uses.

## Google organizations

**What it is.** Everything Google is scoped to one **organization**: a GCP
project, a Pub/Sub topic, a push service account, an OAuth client, and the
Google user Isaac signs in as. An organization is that whole set, not a
namespace wrapped around a single login. There is exactly one config shape,
whether a host serves one organization or several:

```
:google {:acme {:project "…" :topic "…" :oauth {…} :push {…} :health {…}}}
```

A flat `:google {:project "…" …}` (with organization fields sitting directly
under `:google`) is a config error, not a second accepted shape — it
configures **no** organization at all, and any door or login that needs one
refuses cleanly rather than guessing which one was meant.

Most crew-facing work (login, status, the tools below) resolves *which*
organization to act as without being told: the one named explicitly
(`--tenant`, or a comm's own config), else the only one configured. A host
with more than one organization and no explicit name is the one case nothing
can guess for you.

**How to change it.** Configure a new organization by setting its fields
under its own id — every field spans a different concept covered in its own
section below (OAuth, push, health):

```
config set google.acme.project acme-prod
config set google.acme.topic "projects/acme-prod/topics/isaac"
```

**How to verify.** `config get google.acme` (or `handbook__read` topic
`config:google.<organization>.project`, per `isaac.foundation`'s
`config:<path>` reference shape) shows what's configured. `isaac google
status` lists every configured organization's registrations, keyed by
tenant when there's more than one.

### Troubleshooting

- **A field you set under `:google` is reported as an unknown key.** You
  likely wrote it flat (`google.project` instead of `google.acme.project`).
  There is no flat shape — every field lives under an organization id.
- **`isaac google status` prints "No Google organization configured."**
  Nothing is set under `:google`, or what's set there matches the flat shape
  above and was rejected. `config get google` (no `--raw`) shows the
  effective, schema-conformed view.

## OAuth login and scopes

**What it is.** `isaac google login` signs Isaac in as one organization's
Google user with the OAuth authorization-code flow (Google does not offer a
device flow for Chat/Gmail scopes). Tokens are stored under a per-organization
auth-store provider (`google/<organization>`) and refreshed automatically —
nothing in the turn path prompts for re-auth; a stale or revoked refresh
token fails loud, with a message naming which organization to re-sign in for.

The scopes requested are the **union** of every installed module's
contribution to the `:isaac.google/scopes` berth, always including `openid`.
This is not a config value you set directly — it is a consequence of which
modules are installed. Installing `isaac.comm.gchat` adds Chat scopes to the
next login; installing `isaac.comm.gmail` adds Gmail's. **Re-run `isaac
google login --tenant <id>` any time the installed module set changes** —
an existing token only carries the scopes it was granted under, and a call
that needs a newly-added scope fails at Google with "insufficient
authentication scopes" until you do.

isaac-google itself contributes `openid` and
`https://www.googleapis.com/auth/directory.readonly` (the scope behind "Who
spoke," below) — nothing else. **No Cloud Platform scope is requested, and
none should ever be added here.** A Workspace with "Google Cloud console and
SDK session control" applies its reauthentication clock (commonly 16 hours)
to *any* app whose consent screen carries a Cloud Platform scope, non-Google
apps included — one scope here would put Chat, Gmail, and the directory
under that same short clock and kill the refresh token roughly every 15
hours. That is also why isaac-google publishes nothing to Pub/Sub itself
(see Health, heartbeat, and attention) — publishing was the one thing that
used to need that scope.

A login that returns a refresh token good for only 7 days means the Google
Cloud Platform project's OAuth consent screen is still in **Testing** —
publish the app (an *Internal* consent screen, provisioned under the
Workspace organization, has no Testing state and no 7-day cap) and log in
again.

**How to change it.** Set the OAuth client for an organization:

```
config set google.acme.oauth.client-id acme.apps.googleusercontent.com
config set google.acme.oauth.client-secret "${GOOGLE_CLIENT_SECRET}"
config set google.acme.oauth.account ops@acme.example
```

`oauth.account` is informational (printed back on login/status); it doesn't
gate which Google account can actually complete the consent screen. Setting
`oauth.redirect-base` to this host's public HTTPS origin lets the login
finish at this host's own callback instead of pasting a code — see the next
section.

**How to verify.** Run `isaac google login --tenant acme` (add `--code
<code>` to finish a paste-a-code login, or omit it to see the consent URL).
`isaac google status` shows what's actually signed in per organization.
There is no config path for authentication itself — a turn cannot sign
itself in; ask an operator to run the login.

### Troubleshooting

- **Login fails with `google.<organization>.oauth.client-id is required`.**
  No OAuth client is configured for that organization (or the default one,
  if `--tenant` was omitted and more than one is configured). Set
  `oauth.client-id`/`oauth.client-secret` first.
- **A tool call fails with "insufficient authentication scopes."** The
  stored token predates a module that was installed afterward. Re-run
  `isaac google login --tenant <id>` to request the current scope union.
- **The login reports a 7-day refresh token / "still in Testing."** Publish
  the GCP OAuth consent screen (Internal, under the Workspace org) rather
  than leaving it in Testing, then log in again.
- **A refresh keeps failing with `invalid_grant`.** The stored refresh token
  is dead — expired, revoked, or superseded by a later login. Re-run `isaac
  google login --tenant <id>`; the message names the organization.

## The Pub/Sub push door and OAuth callback

**What it is.** One HTTP door, `POST /google/pubsub`, serves every configured
organization. Google's Pub/Sub push carries an OIDC token that isaac-http's
identity layer verifies against a trust rule this module registers **per
configured organization** at server start — issuer, JWKS, the organization's
own `push.endpoint` as audience, and its `push.service-account` as the
required claim. There is no unnamed rule: a host with no `:google` configured
has no door to answer a push with, and refuses every request 401 rather than
keeping an event nobody owns.

Which organization a given push belongs to is settled two ways at once — the
GCP project named in the Pub/Sub subscription, and the service account whose
token the request proved — and both must agree, or the request is refused
403 rather than guessed. A valid push is **persisted and answered 204 before
any handler runs**; see Durable inbox and the worker, below. This route and
its path are fixed — there's no config key that moves it.

`GET /google/oauth/callback` is the other door: where the consent screen
sends the operator's browser back, when `oauth.redirect-base` is set (see
OAuth login and scopes). It carries no credentials of its own — the request
proves itself with the pending login's state nonce and PKCE verifier, good
for ten minutes — so it opens only for that one exchange and nothing else,
and a host with no organization configured registers no callback at all.
Without `redirect-base` set, the login falls back to a pasted `--code`
instead — the only path that works for a host the internet cannot reach.

**How to change it.** The door's *existence* isn't configured directly — it
opens automatically for every organization that has `push.endpoint` and
`push.service-account` set:

```
config set google.acme.push.endpoint https://isaac.acme.example/google/pubsub
config set google.acme.push.service-account pubsub-push@acme-prod.iam.gserviceaccount.com
config set google.acme.oauth.redirect-base https://isaac.acme.example
```

The GCP-side provisioning this pairs with (topic, subscription, IAM,
service accounts) is entirely outside Isaac's own config; see this module's
`doc/rollout.md` for the runbook.

**How to verify.** An unauthenticated `POST /google/pubsub` should answer
401 — `isaac google smoke` checks exactly this (see below). `isaac config
validate` doesn't verify the door's live reachability, only that the config
feeding it is well-formed.

### Troubleshooting

- **A push is refused 401 with no clue why.** Check the `server` log stream
  for `:auth/refused` and its `reason` (`:audience`, `:claims`, or
  `:signature`) — that names which half of the OIDC check failed: a
  mismatched `push.endpoint`, a service account that doesn't match
  `push.service-account`, or a token this host's JWKS fetch can't verify.
- **A push is refused 403, `:google/tenant-mismatch`.** The subscription's
  project and the token's proven service account name two different
  organizations. Fix whichever one is misconfigured — most often a
  subscription pointed at the wrong topic during GCP setup.
- **Every push gets 401, `:google/no-organization`.** Nothing is configured
  under `:google` at all — see Google organizations, above.
- **The callback answers `"unknown to this Isaac"` or `"expired"`.** The
  state the browser came back with doesn't match a pending login this host
  is holding, or it's more than ten minutes old. Run `isaac google login`
  again — logins aren't resumable across a restart or a long delay at the
  consent screen.

## Durable inbox and the worker

**What it is.** A push is written to `<root>/google/inbox/pending/` and
answered 204 before anything reads it — accepting the HTTP request and
acting on it are two different moments. A scheduled worker (inside the
server process only; no plain CLI command drains it) then hands each pending
record to whichever module registered a handler for its event type via the
`:isaac.google/handler` berth, running as the organization the door stamped
onto it. A record whose type no handler claims moves to `inbox/unhandled/`
on first sight (one warning logged) and is never retried; a handler that
throws moves its record to `inbox/failed/` instead, which *is* safe to
requeue by hand. A successfully-handled record moves to `inbox/done/`. A
duplicate delivery (the same Pub/Sub message-id twice — Pub/Sub's own
at-least-once guarantee) is silently dropped at accept time.

None of this is config — it's runtime state under `<root>`, not
`<root>/config`, and the worker's own tick interval is a fixed internal
default, not a config value.

**How to verify.** `isaac google status` prints the count of unhandled
records at the bottom (`inbox: N unhandled`). Pending/done/failed/unhandled
are plain directories under `<root>/google/inbox/` — reachable directly if
a crew's tool grants cover that path, though there's no dedicated tool or
CLI subcommand for inspecting one record's contents.

### Troubleshooting

- **`isaac google status` reports unhandled records.** No installed module
  claims that event's type via `:isaac.google/handler`. Confirm the module
  that should own it (e.g. `isaac.comm.gchat` for Chat message events) is
  actually installed, then clear the stuck record manually — it won't be
  retried automatically.
- **A record sits in `inbox/failed/`.** Its handler threw. Check the
  `server` log for `:google/handler-failed` and its `:error`; once the
  underlying cause is fixed, moving the file back to `inbox/pending/`
  (an operator, filesystem-level) lets the next tick retry it.
- **Pending records seem to accumulate.** Confirm the server process is
  actually running with this module's component started — the worker
  never runs outside it, exactly like the registration timer below.

## Registrations and renewal

**What it is.** Some Google APIs (Workspace Events subscriptions for Chat,
Gmail's `users.watch`) expire and must be renewed before they do, or
recreated if missing entirely. Each contributor (a comm module) tells this
module what it needs via the `:isaac.google/registration` berth; a shared,
hourly timer — one reconcile pass per configured organization, each with
that organization's own token — compares what's configured against what
Google (or, for registrations Google can't list, this module's own
persisted state) reports, then creates, renews, or deletes to match. A
restart also queues one immediate pass rather than waiting up to an hour.

`renew-within-hours` (per organization; default 24) is how far ahead of
expiry the timer acts — a registration inside that window is renewed on the
very next tick, not left to expire first. These registrations stay bound to
the signed-in **human's** token deliberately (never a service account): a
Chat subscription meaning "every space this account belongs to" is a
statement about a person that no service account can make on their behalf.

**How to change it.** 

```
config set google.acme.renew-within-hours 48
```

There's no config for *what* gets registered — that comes entirely from
which comm modules are installed and how they're configured (a Chat space,
a Gmail account to watch); this module only owns the renewal mechanics.

**How to verify.** `isaac google status` lists every registration key this
organization owns, its expiry, and the last event seen under it. `isaac
google smoke` (see below) fails loudly if a key is unregistered, expired, or
inside its renew window with no successful renewal yet.

### Troubleshooting

- **A registration key never appears.** Check that the contributing comm
  module is actually installed and configured — this module only reconciles
  what's contributed to `:isaac.google/registration`, it invents nothing.
- **A registration keeps expiring instead of renewing.** Confirm the server
  process (and this module's component) is actually running — the timer is
  hourly and only ticks inside `isaac server`.
- **`:google/registration-failed` shows up in the log.** The create/renew
  call itself failed at Google — the logged `:reason` is whatever Google's
  API said; a stale/insufficient-scope token is one place to check first
  (see OAuth login and scopes).

## Health, heartbeat, and attention

**What it is.** Every registration tick also evaluates health, per
organization, over three signals: **expiry** (any registration key whose
remote expiry has already passed), **silence** (`silent-after-hours`,
default 6, with no event at all from the organization — judged over every
key this module has ever heard from, since one Chat subscription can cover
a whole workspace and events then arrive under keys nothing explicitly
registers), and a **missed heartbeat** (below). Every condition is logged
and posted to `attention.notify` (owned by `isaac.agent` —
`config:attention.notify.comm` / `config:attention.notify.target`) **only
on the transition**: once when it starts firing, once more when it clears —
a condition still true at the next tick is not news. An **unreached door**
(the server is up but has never answered a single push since boot)
suppresses everything else first: that's exposure, not a Google problem.

**The heartbeat is the part that needs a schedule from outside Isaac.**
isaac-google deliberately publishes nothing to Pub/Sub itself (this is what
keeps the login scope-free of Cloud Platform, above) — instead, an external
publisher (a Cloud Scheduler job, as a Google APIs service account inside
GCP, on its own schedule) puts a marked message on the organization's topic.
This module only **watches for its arrival**. Setting
`health.heartbeat.expected-interval-ms` is what turns that watch on at all —
there's no separate switch, and no default, because only the external
schedule can say what "late" means for a given organization. An organization
that has *never* seen a heartbeat is reported as missed, not silently
skipped — a topic wired up wrong from day one is exactly the case a
first-arrival grace period would hide forever.

**How to change it.**

```
config set google.acme.health.silent-after-hours 8
config set google.acme.health.heartbeat.expected-interval-ms 300000
config set google.acme.health.heartbeat.grace-ms 60000
```

Set `expected-interval-ms` to match whatever schedule the external publisher
actually runs on (see the module's `doc/rollout.md` for the Cloud Scheduler
side) — set it *larger* than the publisher's own period, never smaller, or
the watch alarms continuously. `health.heartbeat.enabled` is retired and
ignored; leaving it set produces a one-time warning at config load, not a
refusal, and doesn't turn anything on or off.

**How to verify.** `isaac config validate` refuses config at load only when
an interval is present but cannot possibly work (zero or negative); a
heartbeat block with no interval at all is a warning, not a refusal — the
organization simply isn't watched. The `server` log stream carries
`:google/silent`, `:google/expired`, `:google/heartbeat-missed`, and
`:google/health-cleared` at the transitions described above. `isaac google
status`'s "door:" line shows whether — and when — the door has ever been
hit.

### Troubleshooting

- **A heartbeat alarms constantly.** `expected-interval-ms` is smaller than
  the external publisher's real schedule, or the schedule stopped running.
  Confirm the Cloud Scheduler job's own last-run time before assuming
  Isaac's side is wrong.
- **`isaac config validate` refuses `expected-interval-ms`.** The value
  isn't a positive number of milliseconds — fix it to the real schedule
  period, in ms, or remove the heartbeat block entirely to stop watching.
- **No attention bulletins show up for a condition you can see in the
  log.** Confirm `attention.notify.comm`/`.target` are actually set —
  `isaac.agent` owns delivery, and an unconfigured target means the
  condition is logged but has nowhere to post.

## Who spoke — people and `google__whois`

**What it is.** Chat hands a human sender's identity as `users/<id>` plus a
display name and never an email; Gmail hands an email and a name and never
an id. isaac-google resolves the join on demand through the Google People
API, under the `directory.readonly` scope it requests by default — nothing
is persisted, and a short in-memory TTL (an hour) keeps a busy thread from
re-asking Google for the same id repeatedly. Every failure here is soft: a
caller gets back whatever it already knew rather than an error.

The same lookup is exposed to crews directly as the `google__whois` tool
(config identity `:google/whois`): give it a `users/<id>` and get a name
and email, or an email and get the matching `users/<id>`, resolved with
Isaac's own token rather than a crew shelling out to a separate CLI with
its own grant.

**How to change it.** There's no tuning knob for the lookup itself. To let a
crew call the tool, allow it like any other (`isaac.agent`'s Tools and
directories):

```
config set crew.acme-support.tools.allow '[:google/whois]'
```

**How to verify.** Call the tool and check its result, or watch for
`google.people/scope-missing` / `google.people/lookup-failed` in the log —
both are warned **once** per reason, not on every failed lookup, so a quiet
log doesn't necessarily mean every call is succeeding, only that the first
failure of each kind was already reported.

### Troubleshooting

- **A lookup always falls back to a bare id or name, never an email.**
  Check the log for `google.people/scope-missing` — the signed-in token
  predates `directory.readonly` being requested, or scope-checking is
  cached from an earlier failure. Re-run `isaac google login`.
- **`google__whois` isn't callable from a crew that should have it.**
  Confirm `:google/whois` is actually on that crew's (or the global default)
  `tools.allow` — see `isaac.agent`'s allow/deny cascade; being installed
  doesn't make a tool callable by itself.

## `isaac google smoke`

**What it is.** A repeatable, live-host check for the whole pipeline above —
door, registrations, inbox, and silence — run against an already-running
server before shipping a version bump. It exists because the untestable
half of this module (a real scheduler tick, a real Google API, a real HTTP
probe) has hidden real defects that no timer-stepped test suite caught.
Each check prints `PASS`/`FAIL` with its evidence; the command exits
non-zero if any check fails: **door** (an unauthenticated push must be
refused 401), **registrations** (every configured key has a live remote
expiry beyond the renew window, per organization), **inbox** (pending count
at or below `--inbox-threshold`, default 0 — unhandled records are reported
but never gate the verdict), and **silent** (currently-firing
silence/heartbeat conditions at or below `--silent-threshold`, default 0).

`--send-live` is retired: this module no longer publishes anything, so
there's nothing left for it to send — the `silent` check above, reading the
externally-published heartbeat, is the check that replaced it.

**How to change it.** Not a config path — this is a CLI-only diagnostic, not
something a turn runs on its own. Options live on the command itself
(`--tenant`, `--url`, `--renew-within`, `--inbox-threshold`,
`--silent-threshold`); see `isaac google smoke --help`.

**How to verify.** Run it: `isaac google smoke --tenant acme`. See this
module's `doc/rollout.md` for the full defect-to-check mapping and when to
run it as part of a rollout.

### Troubleshooting

- **`smoke` fails on `door` but `isaac google status` looks fine.** `status`
  never probes the door over HTTP; `smoke` does. Confirm the server is
  actually reachable at the URL `smoke` used (`--url` overrides the
  default, this host's own resolved HTTP port).
- **`smoke` fails on `registrations` right after a fresh install.** The
  registration timer runs hourly; a brand-new organization may not have
  had its boot-time immediate tick land yet. Check `isaac google status`
  before assuming `smoke` is wrong.
