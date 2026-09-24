# Develop integration verification — 24 September 2026

- Backend: `./gradlew check bootJar --rerun-tasks` passed, 7 tests, none skipped. Real disposable PostgreSQL covered migration history, status contract, security denial, readiness/liveness, and database outage/recovery. An executable JAR was built.
- Frontend: 4 Jest tests, strict type checking, and production build passed.
- Browser: `E2E_PORT=4320 npm run test:e2e` passed all 4 Chromium tests, covering backend communication/retry, mobile layout, and the public landing page while API requests are unavailable.
- Packaged backend HTTP probes: readiness and liveness returned UP; `/api/status` returned the expected UP data/error envelope.
- Routes: `/` is the Orbit landing page; `/status` preserves the backend connection diagnostic. The landing page requires no API or login.
- Architecture baseline consulted: `ab3aa138af3f1b0d23d12661cc1c5849ab54a5ab`.

The local verification stack uses an isolated Compose project `betterf-develop-check` on PostgreSQL port 55433, with the packaged backend on 8080. Credentials are generated into an owner-readable temporary environment file, never committed. Existing `.env` and database data are preserved.

CI is configured to run backend, frontend, and Chromium checks; its remote result is separate from these local checks.
