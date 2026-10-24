# theeverthread rename and deployment cutover

The product, Java namespace (`com.theeverthread`), Gradle project
(`theeverthread-backend`), npm package (`theeverthread-frontend`), Spring
configuration prefix, metrics, browser refresh lock, email defaults, and Compose
projects now use `theeverthread`. CI publishes
`ghcr.io/samehadel/theeverthread-backend` and
`ghcr.io/samehadel/theeverthread-frontend`; image names are independent of the
repository name. The executable JAR remains `app.jar` so CI and Docker agree.

## Current live status (2026-10-24)

Both GitHub repositories have been renamed and their IDs verified. The
application OIDC subject now contains `theeverthread`; local remotes are updated.
`AWS_DEPLOY_ENABLED=false` is intentional until cutover completes. No AWS server,
IAM role, database, or live image has been changed: authenticated AWS access is
unavailable. Development now stores the owner-supplied candidate role ARN
`arn:aws:iam::129896631580:role/betterf-ec2-role`; its OIDC trust
and permissions remain unverified. Production still contains an EC2 instance
ARN. The current GitHub CLI token cannot list packages (`read:packages` is missing).
The owner approved committing and pushing the application and architecture
changes on `codex/rename-theeverthread` branches. Live AWS cutover remains gated.

## Compatibility

- Existing Liquibase SQL files are byte-for-byte unchanged. Their `betterf`
  changeset authors are persisted migration identities, not active branding.
  Renaming them would cause applied DDL to run again. Use `theeverthread` for
  authors of new migrations. Do not clear checksums or edit DATABASECHANGELOG.
- Existing Linear links retain the real `linear.app/betterf` workspace address.
  The tracker workspace and issue prefix have not been renamed.
- Replace custom `betterf.*` properties with `theeverthread.*`, and
  `BETTERF_*` environment overrides with `THEEVERTHREAD_*`. Generic settings
  such as `DB_URL`, `PUBLIC_ORIGIN`, and `REFRESH_TOKEN_LIFETIME` retain their names.
- The refresh cookie is now `THEEVERTHREAD_REFRESH`. Existing users need to
  sign in again after their short session expires. Deploy backend and frontend
  together; old and new tabs must not perform refresh concurrently during cutover.
- The managed Neon database is selected by `DB_URL`. Preserve its URL and data
  during this application rename. Renaming a provider project is separate from
  changing the database hostname, credentials, schema, or contents.

## Existing local PostgreSQL data

Fresh installations use database `theeverthread` and volume
`theeverthread_postgres-data`. Changing the Compose project name otherwise
selects a fresh volume. Before starting against existing data:

1. Identify the existing Compose project and PostgreSQL volume with
   `docker compose ls` and `docker volume ls`. Back up the database and verify
   recovery before changing its name. Keep the existing `.env` credentials.
2. Stop the old local stack with its original Compose and environment files,
   without `-v`. Set `POSTGRES_VOLUME` to the existing volume name,
   `DB_NAME=betterf`, and `DB_URL` to that existing database's JDBC URL.
   Start the renamed stack with these overrides to keep using the same data.
3. If the physical database must also be renamed, stop every database client,
   connect to the maintenance database `postgres` with an administrator, and run
   `ALTER DATABASE betterf RENAME TO theeverthread;`. Update `DB_NAME` and
   `DB_URL` together before restarting clients. PostgreSQL initialization variables
   do not rename an initialized database or change its password.
4. To remove the old physical volume name, perform a verified backup/restore into
   a new named volume during a separate maintenance window. Keep the original
   volume until the restore and application checks pass. Never use `down -v`
   against an existing installation as a rename mechanism.

## Live cutover sequence

Do not enable deployment until every applicable step passes.

1. Record running image digests, Compose projects, server paths, and all GitHub
   environment variables. Disable `AWS_DEPLOY_ENABLED` and wait for active
   deployments to finish. The rename intentionally leaves this gate disabled
   while AWS access and configuration are unresolved.
2. Rename the application repository to `Samehadel/theeverthread` and the
   architecture repository to `Samehadel/theeverthread-architecture`. Repository
   IDs stay `1380628408` and `1380634286`. Update local remotes and clone paths
   where appropriate; other worktrees and IDE project locations require their
   own updates. Git history retains historical names.
3. With authenticated AWS access, inspect the actual deployment IAM roles.
   Development currently targets account `129896631580`, region `eu-central-1`,
   instance `i-0c895901443d5905b`; production is configured for instance
   `i-0e85563bfb9a596e9` in that same account and region. The checked-in workflow
   deploys only development. Do not enable production as part of this rename.
4. Apply the development trust and permissions policies in `deploy/aws/` to
   the actual deployment role. Inspect the repository's OIDC customization and
   verify its subject is exactly
   `repo:Samehadel@24625713/theeverthread@1380628408:environment:development`.
   These templates target development only. Production requires its own role,
   instance scope, and `environment:production` subject if its deployment is
   configured separately. IAM role names cannot be renamed in place: create
   an equivalent role with the new name when necessary, verify its policies,
   and update the environment role ARN before retiring any old role.
5. Set `AWS_DEPLOY_ROLE_ARN` in each configured environment to its verified
   `arn:aws:iam::129896631580:role/...` ARN. At inspection on 2026-10-24,
   both environments incorrectly contained EC2 instance ARNs. Do not guess
   replacement role ARNs. Check EC2 Name tags and other resource display names
   for the former brand; preserve instance IDs, database URLs, and credentials.
6. On the development host, under a maintenance window, stop the existing
   application using its original `/opt/betterf/compose.yaml`, `secrets.env`,
   and successful `current.env`. Use `docker compose down` **without `-v`**.
   Move `/opt/betterf` to `/opt/theeverthread`, preserving ownership, permissions,
   `secrets.env`, `current.env`, and `previous.env`. Install the new `compose.yaml`
   and `deploy.sh` there as root with directory/script mode 700 and secret/env
   mode 600. Stop the old Compose project before starting `theeverthread-dev`
   so the old frontend does not retain port 80. Preserve the existing Neon URL.
7. Migrate any custom property overrides in `secrets.env`; preserve the real
   mail sender, SMTP configuration, and `PUBLIC_ORIGIN`. Prepare registry pull
   access for both new image packages. Old GHCR package names cannot simply be
   changed by renaming the GitHub repository: publish the two new packages and
   verify host pull access. Keep old image digests available for rollback.
8. Once the code has been committed and pushed with the owner's authorization,
   use CI to check and publish the new images. Enable `AWS_DEPLOY_ENABLED=true`
   only after host setup, IAM role variables, and trust match. Dispatch the
   workflow on `develop`, verify the SSM result, new Compose project, readiness,
   `/api/status`, landing branding, email delivery, and login/refresh/logout.
9. Roll back using the recorded old deployment files, old Compose project,
   old paths, and old image digests together if necessary. Stop the new project
   before starting the old one. Revert property overrides and expect users to
   sign in again. Never roll back by deleting data or rewriting migrations.

## Source and validation

Architecture baseline: `cdd59b639c3136a6311640b7f403a5aca60af5ba`, fetched and
matched to `origin/main` on 2026-10-24. The separate primary architecture checkout
has unrelated uncommitted deployment/versioning decisions; these were not copied
or overwritten. Rename documentation changes are in this workspace's ignored,
separate `architecture/` Git repository and must be reviewed there separately.

Final local verification on 2026-10-24: 132 backend tests, 91 frontend unit tests,
and 22 Playwright browser tests passed. Backend executable packaging, frontend
production build, TypeScript checking, five version-script tests, YAML/JSON and
shell/Python syntax checks, and both Compose configurations passed. The browser
suite used an isolated PostgreSQL/Mailpit stack, removed after verification.
The final source audit found the former name only in immutable migration IDs,
actual Linear addresses, and explicit legacy/cutover documentation. All existing
database migration files were verified byte-for-byte against HEAD.
