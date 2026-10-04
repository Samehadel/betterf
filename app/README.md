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

English is the temporary foundation resource language, not a decision on supported product languages. Organization onboarding and session authentication are implemented in the identity module. Invitation/member workflows and password recovery remain separate work. No demonstration product accounts are installed.


## Organization registration (BTF-5)

The landing page links to `/register` and `/login`. Registration persists a pending
organization, pending account, and a separate reusable verification email record.
Only email verification activates the organization/account and establishes ADMIN
access. Pending organizations do not reserve domains; only one can become active
for a domain. Login requires both statuses to be ACTIVE.

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

Email state is PENDING/SENT/FAILED, with attempt count, timestamps, and a safe
failure code. SENT means SMTP accepted delivery, not confirmed inbox delivery.
Failed sends retain pending data and a previous working credential. Request a
new email to retry; successful resends rotate the credential after a 60-second
cooldown. Links expire at 24 hours. Repeated form submission never overwrites
an existing pending account's password/profile. Pending data older than 30 days
is removed in hourly batches of at most 100. Invitations and member views remain
separate stories. Password recovery ownership and ingress abuse controls are
release dependencies; this story does not deploy the account-access experience.

The API contract includes CSRF, session login/logout, and the registration routes.
Run `./gradlew check bootJar` for PostgreSQL integration and module checks, and
frontend `npm test`, `npm run typecheck`, `npm run build`, and `npm run test:e2e`.
Onboarding browser tests require Mailpit (HTTP API defaults to localhost:8025;
override `MAILPIT_URL`) and a fresh disposable database. They send only local
captured test emails.
