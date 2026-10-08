# BetterF application foundation

Implementation of [BTF-3](https://linear.app/betterf/issue/BTF-3). Spring Boot backend, Angular shell, PostgreSQL, Liquibase, and automated checks. BTF-5 adds organization registration, administrator email verification, and password-based account access.

## Repository layout

The current Git checkout is rooted above this directory: application code lives under `app/backend` and `app/frontend`; CI lives at `.github/workflows/ci.yml`. The architecture repository is a separate sibling at `architecture/`. The original workspace source map describes a separate application checkout; this implementation preserves the root repository layout observed during setup. Run the commands below from `app/` unless another directory is stated.

Architecture baseline consulted for develop integration: `ab3aa138af3f1b0d23d12661cc1c5849ab54a5ab`. Backend contracts are recorded in `backend/src/main/resources/openapi.yaml`. The removed `foundation.md` is not required.

## Prerequisites

- JDK 25 (`java -version`). `JAVA_HOME` must point to that JDK.
- Node 22.23.1 and npm 10.9.8 (`node --version`, `npm --version`). With nvm, run `nvm install` from `frontend/`.
- Docker Engine/Desktop running with Docker Compose v2 (`docker info`, `docker compose version`).
- Git, Bash, curl, and network access for initial Gradle/npm dependencies and PostgreSQL/Chromium downloads.

The checked-in Gradle 9.7.1 wrapper downloads its distribution and verifies the pinned checksum. No global Gradle install is needed. Backend framework versions are pinned in `backend/build.gradle`; resolved versions are locked in `backend/gradle.lockfile`. Frontend versions are exact and `package-lock.json` is committed.

## Prepare a fresh checkout

Clone the application repository and enter its `app/` directory:

```bash
git clone https://github.com/Samehadel/betterf.git
cd betterf/app
cp .env.example .env
```

Set `DB_PASSWORD` in `.env` to a new local-only value. A shell-safe value can be generated with `openssl rand -hex 24`. The launchers source `.env` as trusted local shell configuration; quote values containing shell metacharacters. Do not commit this file. No default password is provided.

```bash
docker compose up -d --wait
cd frontend
npm ci
cd ..
```

PostgreSQL listens only on loopback, uses database `betterf`, and persists in the project-owned `betterf_postgres-data` volume. On first startup PostgreSQL initializes credentials from `.env`. Changing `.env` later does not change a password already stored in that volume.

## Run

From `app/`, in separate terminals:

```bash
./scripts/backend.sh
```

```bash
./scripts/frontend.sh
```

Open http://127.0.0.1:4200 for the Orbit landing page. Open http://127.0.0.1:4200/status for the backend diagnostic screen, which should display **Connected**. Stop the backend, choose **Check again**, and confirm **Backend unavailable** appears. Restart the backend and choose **Try again** to reconnect.

```bash
curl --fail http://127.0.0.1:8080/actuator/health/readiness
curl --fail http://127.0.0.1:8080/actuator/health/liveness
curl --fail http://127.0.0.1:8080/api/status
```

Expected status body: `{"data":{"status":"UP"},"error":null}`. Readiness includes PostgreSQL; liveness does not. Liquibase runs a harmless SQL baseline and records migration history on startup. There are no product tables. Hibernate schema mutation is disabled.

Use Ctrl-C in each application terminal and `docker compose down` to stop supporting infrastructure while retaining data. `docker compose down -v` deletes this project's database volume; use it only when its local data is disposable.

## Configuration

| Variable | Purpose / default |
|---|---|
| `DB_PASSWORD` | Required local PostgreSQL password |
| `DB_USER` | Required backend username; `.env.example` uses `postgres` |
| `DB_PORT` | Compose host database port, `5432` |
| `DB_URL` | Backend JDBC URL, `jdbc:postgresql://127.0.0.1:5432/betterf` |
| `SERVER_PORT` | Backend port, `8080` |
| `BACKEND_URL` | Angular development proxy target, `http://127.0.0.1:8080` |
| `SERVER_ADDRESS` | Backend bind address, `127.0.0.1` |

If changing `DB_PORT`, change the port in `DB_URL` too. If changing `SERVER_PORT`, update `BACKEND_URL`. Browser requests use relative `/api` URLs. The production frontend build requires a host serving the SPA and proxying `/api` on the same origin; production deployment is not supplied by this story.

Spring Security permits the status/probe endpoints and the documented registration and authentication endpoints. Mutations require CSRF; company access requires an authenticated account and active account/organization statuses. Unknown endpoints remain denied and no default development user is generated. The handwritten OpenAPI contract is `backend/src/main/resources/openapi.yaml`; it is packaged as a resource, not exposed as a public documentation endpoint.

## Verification email scheduling

Verification delivery uses JDBC ShedLock and the Liquibase-managed `SHEDLOCK` table.
The scheduler calls the worker through its Spring proxy so `@SchedulerLock` also
protects direct worker invocations. Competing invocations skip work; the lease is
released after completion or failure. Database time controls lease expiry.

`betterf.registration.delivery.enabled=false` disables automatic delivery, and
`betterf.registration.delivery.delay` defaults to 5000 milliseconds.
`betterf.registration.delivery.lock-at-most-for` defaults to `10m`; keep it longer
than the maximum duration of a 25-email batch, including SMTP and database calls.
An invocation still running after lease expiry can overlap another worker.

When upgrading from the advisory-lock worker, stop or disable delivery on all old
instances before enabling the new worker: the two locking mechanisms do not
coordinate. Liquibase creates the lock table at startup. Rolling back the code can
leave this additive table in place; dropping it requires all ShedLock workers to
be stopped.

## Application version

The repository root `VERSION` is authoritative. Gradle reads it; frontend package
metadata is synchronized with `node scripts/version.mjs --sync` from the repository
root. CI runs `--check` and fails on drift. Ordinary PRs do not need version bumps;
commits and image digests identify development deployments. See the
[versioning workflow](../architecture/versioning.md) for daily work and release preparation.

The executable backend artifact is always `backend/build/libs/app.jar` relative
to this directory, independent of the release number.

## Verify

Backend checks run against their own disposable PostgreSQL container and do not use `.env` or the local development database:

```bash
cd backend
./gradlew check bootJar
cd ../frontend
npm test
npm run build
npx playwright install chromium
```

With the backend running (the browser runner starts its own Angular server on port 4200):

```bash
npm run test:e2e
```

If port 4200 is already occupied, run `E2E_PORT=4320 npm run test:e2e` to test on a separate port. If the backend uses a custom port, run `BACKEND_URL=http://127.0.0.1:YOUR_PORT npm run test:e2e`.

Backend coverage includes real startup/migration history, HTTP envelopes and exclusions, security denial, database outage/recovery, and Spring Modulith boundaries. Jest covers loading, success, malformed responses, service failure/retry, and timeout recovery. Playwright covers live backend communication, a browser-controlled network outage and real reconnect, keyboard interaction, and a narrow viewport. The outage interception verifies client recovery; the backend database outage is tested separately with a real paused PostgreSQL container.

Reports: `backend/build/reports/tests/test/index.html`, `backend/build/reports/jacoco/test/html/index.html`, and Playwright failure traces in `frontend/test-results/`. CI executes the same checks on Linux, installs Chromium, starts the packaged backend with PostgreSQL, and verifies browser communication.

To intentionally refresh dependency locks after editing versions, run `./gradlew dependencies --write-locks` in `backend/` and `npm install` in `frontend/`, then review lockfile changes and rerun checks.

## Troubleshooting

- **Docker unavailable:** start Docker Desktop/Engine and verify `docker info`. Integration tests require Docker and fail rather than silently skip.
- **Port already in use:** use an unused `DB_PORT` and matching `DB_URL` in `.env`; update backend/proxy ports together if necessary. This machine's verification used PostgreSQL port `55432` because `5432` was occupied.
- **Password authentication failed:** verify `.env` and remember existing database volumes retain their original password. Restore the original local password or deliberately recreate a disposable volume.
- **Backend will not start:** verify JDK 25, `DB_PASSWORD`, and `docker compose ps`. Initial dependency downloads require internet access. Liquibase/database failure must be fixed before readiness succeeds.
- **UI reports unavailable:** inspect the readiness URL, check `BACKEND_URL`, and restart Angular after changing proxy configuration. Requests time out after five seconds and the retry button remains available.
- **Browser tests cannot start:** stop the existing frontend on 4200, ensure backend readiness, and run `npx playwright install chromium`. Linux may need `npx playwright install --with-deps chromium`.
- **Changes to migrations:** do not edit already-applied changesets. Add a new SQL changeset and explicit include. The initial `SELECT 1` changeset has a non-destructive no-op rollback; future product migrations need their own recovery design.

## Scope limits

For the first AWS development pipeline and server setup, see
[AWS deployment](../deploy/aws/README.md). Deployment remains disabled until its
repository setting and AWS resources are configured.

English is the temporary foundation resource language, not a decision on supported product languages. Organization onboarding and session authentication are implemented in the identity module. Member acceptance, invitation management, and password recovery remain separate work. No demonstration product accounts are installed.


## Organization registration (BTF-5)

The landing page links to `/register` and `/login`. Registration persists a pending
organization, pending account, and a separate reusable verification email record.
Only email verification activates the organization/account and establishes ADMIN
access. Pending organizations do not reserve domains; only one can become active
for a domain. Login requires both statuses to be ACTIVE.

New passwords require 10–128 characters, an uppercase letter, and a special
character (punctuation or symbol). Spaces alone do not satisfy the special-character
requirement. Browser and API apply the same checks; existing login passwords are
not revalidated against the new registration policy.

Opening a valid email link automatically submits the token to the CSRF-protected
verification API. First-time verification creates the authenticated session and
rotates the session ID and CSRF token; the browser goes directly to `/company`.
The `/verify` route is only a transient link handler, with recovery shown for invalid
or expired links. No email re-entry or confirmation click is needed for valid links.
An already-used link cannot create another session: an existing matching session
returns home, otherwise the page offers password login.

This follows the product owner's 2026-10-04 correction to BTF-5's earlier
no-company-status wording. Architecture baseline: `0765baaa675be6232b9df9c025d415dca1ab3f49`;
implementation decisions are in architecture ADR 0005.

For local email capture, run `docker compose up -d --wait` with the supplied
Mailpit service. Open http://127.0.0.1:8025 to read verification emails.
For local HTTP only, set `SESSION_COOKIE_SECURE=false` in the backend environment.
Set `PUBLIC_ORIGIN` to the browser origin (default http://127.0.0.1:4200).
`SMTP_HOST` defaults to 127.0.0.1 and `SMTP_PORT` to 1025. Production requires a
configured SMTP sender and credentials (`MAIL_FROM`, `SMTP_USER`, `SMTP_PASSWORD`,
`SMTP_AUTH=true`, `SMTP_STARTTLS=true`) and HTTPS with secure session cookies.
The deployment compose file must receive these settings before public release.

Email sending runs in a background worker every five seconds, in batches of 25.
A PostgreSQL session advisory lock on a dedicated connection prevents overlapping
runs across application instances. Use direct PostgreSQL or session pooling;
transaction-mode PgBouncer cannot hold this lock. Allow at least two connections
per worker instance. `BETTERF_REGISTRATION_DELIVERY_ENABLED=false` disables the
scheduler; `BETTERF_REGISTRATION_DELIVERY_DELAY` sets its interval in milliseconds.

The additive migration converts existing SENT records to SMTP_ACCEPTED and schedules
existing PENDING/FAILED records. Stop old application instances before applying it:
the earlier application cannot read the new delivery statuses.

States are PENDING, SENDING, SMTP_ACCEPTED, FAILED, and CANCELLED. The worker commits
SENDING before contacting SMTP. SMTP_ACCEPTED means the relay accepted the message,
**not confirmed inbox delivery**. SMTP_MESSAGE_ID supports provider-log correlation.
The UI polls status and distinguishes queued, failed/retrying, and accepted.
Failures record a safe code and retry after 60, 120, 240, 480, then 900 seconds,
with later delays capped at 900 seconds. Repeated requests coalesce while queued
or retrying. An accepted replacement rotates the token; a failure preserves the
previous valid token. Acceptance starts the 60-second resend cooldown; links expire
after 24 hours. Ineligible registrations are cancelled.

The worker never picks up SENDING rows. A crash or database failure after SMTP
submission may leave one for investigation. Check provider logs and stop all
workers before manually returning a confirmed abandoned attempt to FAILED with
NEXT_ATTEMPT_AT set to CURRENT_TIMESTAMP. Never reset a live attempt. SMTP and
PostgreSQL cannot commit atomically; an ambiguous network failure can cause duplicate
messages on retry. No raw token is stored for replay.

Repeated submission never overwrites an existing pending account's profile. Hourly
cleanup removes pending data older than 30 days in batches of 100, excluding SENDING.
Member acceptance, invitation management, member views, password recovery, and ingress abuse controls remain
separate release dependencies.

### Brevo SMTP configuration

Supply these in the backend process environment (or source your untracked `app/.env`
before starting Java; Spring Boot does not automatically load that file):

```dotenv
SMTP_HOST=smtp-relay.brevo.com
SMTP_PORT=587
SMTP_USER=<Brevo SMTP login>
SMTP_PASSWORD=<Brevo SMTP key, not the API key>
SMTP_AUTH=true
SMTP_STARTTLS=true
MAIL_FROM=<sender address verified in Brevo>
PUBLIC_ORIGIN=http://127.0.0.1:4200
SESSION_COOKIE_SECURE=false
```

Use HTTPS and `SESSION_COOKIE_SECURE=true` outside local HTTP development. The SMTP
login and MAIL_FROM are different settings. The default `no-reply@betterf.local`
is rejected for remote SMTP with `SMTP_FROM_NOT_CONFIGURED`. Authentication errors
use `SMTP_AUTHENTICATION_FAILED`; other submission failures use `SMTP_DELIVERY_FAILED`.
Fix configuration and restart; FAILED rows retry when due.

If Brevo receives a message but does not deliver it, inspect **Transactional > Logs**
by recipient/time or SMTP_MESSAGE_ID for bounce, block, and suppression results.
Check sender/domain verification and whether transactional sending is active.
Accepted submissions are not retried automatically because downstream delivery
cannot be inferred from SMTP. Provider webhooks are outside this change. References:
[Brevo SMTP troubleshooting](https://help.brevo.com/hc/en-us/articles/115000188150-Troubleshooting-Issues-with-Brevo-SMTP)
and [transactional logs](https://help.brevo.com/hc/en-us/articles/360021533839-Manage-your-transactional-logs-and-email-previews).

The API contract includes CSRF, session login/logout, and the registration routes.
Run `./gradlew check bootJar` for PostgreSQL integration and module checks, and
frontend `npm test`, `npm run typecheck`, `npm run build`, and `npm run test:e2e`.
Onboarding browser tests require Mailpit (HTTP API defaults to localhost:8025;
override `MAILPIT_URL`) and a fresh disposable database. They send only local
captured test emails.

## Team invitations

Verified administrators can open **Invite a colleague** from the company home.
`/company/invitations` sends one email per row. A successful relay submission locks
its row and exposes **Add another invitation**; failed sends remain editable.
**Previous invitations** loads saved company records each time the page opens,
including after navigation or refresh. Older records are available with **Load more
invitations**; this section is read-only and never resends email.
Uncertain responses offer **Check send status** rather than an automatic resend.
SMTP acceptance does not guarantee inbox delivery.

`INVITATION_MAX_ACTIVE_ACCOUNTS` sets the default active-account cap (10). A positive
`ORGANIZATION.MAX_ACTIVE_ACCOUNTS` overrides it for an individual organization.
Active administrators count toward the cap; pending invitations do not. Invitation
links expire after seven days; administrator verification retains its 24-hour expiry.
HTML invitation emails are stored in backend `resources/mail/team-invitation.html`
and use the same SMTP and PUBLIC_ORIGIN configuration as verification emails.

The invitation link opens member registration at `/invitation/accept`. Recipients
explicitly accept and create their member account, then sign in automatically.
Pending-management navigation explains that resend/revoke tools arrive with BTF-8.
Acceptance cancels any unfinished administrator signup for the invited email and invalidates its verification links.
Used links provide a password-login next step and cannot repeat registration. Provider submission and database commits cannot be atomic;
SENDING records left by a crash or persistence failure require operator investigation
before retry. See [invitation acceptance](../architecture/backend/invitation-acceptance.md).

## Session expiry recovery

The browser now restores expired sessions through `POST /api/auth/refresh` and retries
requests rejected by authentication before execution. Refresh credentials rotate and
live in a Secure, HttpOnly, SameSite=Strict cookie scoped to `/api/auth`; the database
stores only hashes. `REFRESH_TOKEN_LIFETIME` defaults to `14d` with an absolute expiry.
Logout revokes this browser's refresh credential. Password changes and inactive
accounts/organizations prevent renewal. Sessions established before this deployment
need one new login to obtain a refresh cookie.

Anonymous protected requests now return `401 AUTHENTICATION_REQUIRED`; CSRF rejection
returns `403 CSRF_INVALID`, and permission denial remains `403 ACCESS_DENIED`. The UI
refreshes CSRF after restoration and does not replay network failures or generic
permission errors. Exhausted refresh credentials lead to login. Concurrent requests
share recovery; browsers supporting Web Locks also coordinate across tabs.

Apply the additive refresh-session migration with the backend deployment and deploy
the matching frontend. Architecture consulted: `16a2955` on architecture `origin/main`;
the proposed decision is in `architecture/decisions/0005-session-refresh.md`.
