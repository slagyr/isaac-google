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
