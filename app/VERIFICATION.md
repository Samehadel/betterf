# BTF-3 verification

Verified locally on 2026-09-22 (Africa/Cairo), macOS arm64, Java 25.0.1, Node 22.23.1, npm 10.9.8, Docker Engine 28.3.2.

Architecture baseline: `6d36b622ee93b719abf37e1500e904069bd23421`, matching freshly fetched `origin/main`. Architecture updates for this implementation are uncommitted changes in the separate architecture repository.

| Check | Result |
|---|---|
| `backend/./gradlew check bootJar` | Passed: 7 tests; executable JAR and JaCoCo report generated |
| `frontend/npm test` | Passed: 4 Jest/TestBed tests |
| `frontend/npm run build` | Passed: Angular production bundle, approximately 248 kB initial raw size |
| `frontend/npm run test:e2e` | Passed: 3 Chromium tests against the real backend and local PostgreSQL |
| `docker compose up -d --wait` | Passed on local DB port 55432; default port 5432 was already occupied |
| `scripts/backend.sh`, `scripts/frontend.sh` | Both startup commands verified; browser visibly shows Connected |
| HTTP status/readiness probes | Expected JSON and HTTP 200 verified |
| npm dependency audit | Zero reported vulnerabilities after pinning patched PostCSS 8.5.28 |
| Shell syntax, CI YAML parsing, Git whitespace checks | Passed |

Backend tests use disposable PostgreSQL 17.6 containers, real Liquibase migrations, and real HTTP startup/security/readiness checks. Pausing PostgreSQL produces HTTP 503 readiness/status while liveness stays HTTP 200; unpausing recovers. MVC fixture tests separately verify response wrapping, preserved headers/status, no-content/download exclusions, and safe unexpected-error responses.

The browser suite verifies real frontend/backend communication, keyboard retry after an intercepted connection refusal, and a 375px viewport without horizontal overflow. Only the browser outage is substituted; recovery contacts the running backend. The shell was also visually inspected in the in-app browser.

The workflow is configured but has not run on GitHub because no commit or push was requested. Linux CI remains unverified. No tests were skipped. Product authentication, business models, and production deployment are out of scope.

The local preview remains running on http://127.0.0.1:4200 with backend on 8080 and project PostgreSQL on 55432. Its ignored `.env` contains a generated local-only password; no credentials are committed. Stop the applications with Ctrl-C in their launcher terminals and run `docker compose down` from `app/` to stop PostgreSQL while preserving its volume.
