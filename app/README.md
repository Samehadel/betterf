# BetterF application foundation

Implementation of [BTF-3](https://linear.app/betterf/issue/BTF-3). Spring Boot backend, Angular shell, PostgreSQL, Liquibase, and automated checks. Product features and account workflows are intentionally deferred.

## Repository layout

The current Git checkout is rooted above this directory: application code lives under `app/backend` and `app/frontend`; CI lives at `.github/workflows/ci.yml`. The architecture repository is a separate sibling at `architecture/`. The original workspace source map describes a separate application checkout; this implementation preserves the root repository layout observed during setup. Run the commands below from `app/` unless another directory is stated.

Architecture consulted: `6d36b622ee93b719abf37e1500e904069bd23421`, verified against freshly fetched `origin/main`. Delivered design and contracts: `../architecture/foundation.md`. Significant implementation choices are recorded in `../architecture/decisions/0002-local-foundation.md`.

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

Open http://127.0.0.1:4200. The shell should display **Connected**. Stop the backend, choose **Check again**, and confirm **Backend unavailable** appears. Restart the backend and choose **Try again** to reconnect.

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
| `DB_USER` | PostgreSQL username, `betterf` |
| `DB_PORT` | Compose host database port, `5432` |
| `DB_URL` | Backend JDBC URL, `jdbc:postgresql://127.0.0.1:5432/betterf` |
| `SERVER_PORT` | Backend port, `8080` |
| `BACKEND_URL` | Angular development proxy target, `http://127.0.0.1:8080` |
| `SERVER_ADDRESS` | Backend bind address, `127.0.0.1` |

If changing `DB_PORT`, change the port in `DB_URL` too. If changing `SERVER_PORT`, update `BACKEND_URL`. Browser requests use relative `/api` URLs. The production frontend build requires a host serving the SPA and proxying `/api` on the same origin; production deployment is not supplied by this story.

Spring Security permits only GET status and the two probe endpoints. All other routes are denied, CSRF remains enabled, and no default development user is generated. The handwritten OpenAPI contract is `backend/src/main/resources/openapi.yaml`; it is packaged as a resource, not exposed as a public documentation endpoint.

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

Stop a manually started frontend first to free port 4200. If the backend uses a custom port, run `BACKEND_URL=http://127.0.0.1:YOUR_PORT npm run test:e2e`.

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

English is the temporary foundation resource language, not a decision on supported product languages. Authentication, business schema, domain modules, production hosting, and product workflows remain future work. MapStruct/Lombok, JPA, localization, routing, and state tooling are configured without adding demonstration product data.
