# AWS development deployment

For existing installations, follow the [rename cutover](../../docs/RENAMING.md) before deploying: server paths, Compose projects, GHCR packages, and IAM trust must move together.

The root `.github/workflows/ci.yml` runs application checks, publishes the tested
backend and frontend as GHCR images, and deploys their immutable digests to one
x86_64 Ubuntu EC2 server through Systems Manager. Only `main` can publish or
deploy. Pull requests run checks only.

Deployment is disabled until the repository Actions variable
`AWS_DEPLOY_ENABLED` is set to `true`. Keep it unset while preparing AWS.

## First deployment

1. Prepare an x86_64 Ubuntu 24.04 EC2 instance with Docker Engine and Compose.
   Attach an instance role with `AmazonSSMManagedInstanceCore` and verify Session
   Manager connectivity. Configure a cost budget and outbound registry/SSM access.
2. Install this directory's `compose.yaml` and `deploy.sh` in `/opt/theeverthread/`
   on the server. Use a root-owned directory with mode 700 and make `deploy.sh`
   executable with mode 700. Create root-only `/opt/theeverthread/secrets.env` with a
   Neon `DB_URL` (JDBC URL with TLS), `DB_USER`, and `DB_PASSWORD`. Use the development
   branch direct endpoint for startup Liquibase migrations. Never overwrite credentials
   as part of deployment.
3. Run `sudo docker login ghcr.io --username YOUR_GITHUB_USERNAME` on EC2,
   entering a classic GitHub personal access token with `read:packages` at the
   password prompt. Renew this credential before its expiration.
4. Create the GitHub OIDC role using the two JSON policies in this directory.
   They target account `129896631580`, region `eu-central-1`, and instance
   `i-0c895901443d5905b`. The trust policy uses the verified immutable GitHub
   subject prefix `repo:Samehadel@24625713/theeverthread@1380628408` and the
   `production` environment. In IAM, add the GitHub OIDC provider with audience
   `sts.amazonaws.com`, create `theeverthread-github-deploy` using the trust policy,
   and attach the permissions policy as an inline policy.
5. Create the GitHub environment `production`, restrict it to the `main`
   branch, and set environment variables `AWS_REGION`, `AWS_DEPLOY_ROLE_ARN`,
   and `EC2_INSTANCE_ID`. The OIDC trust subject must match this environment.
6. Set the **repository** variable `AWS_DEPLOY_ENABLED=true`. After these files
   are committed and pushed, run “theeverthread checks and deployment” from Actions on
   `main`, or push a new commit to `main`.
7. Check the deploy job's SSM result, then visit the server's public IP and
   `/status`. Limit inbound HTTP to your own IP for this development setup.

The workflow reads the files in this directory. Changes to server-side Compose or `deploy.sh` must be copied to EC2
explicitly before deploying code that depends on them.

For an existing environment restricted to `develop`, update its deployment branch
policy to allow `main` before running deployment. To deploy manually, open GitHub
Actions, select “theeverthread checks and deployment”, choose **Run workflow**, select
`main`, and run it. `AWS_DEPLOY_ENABLED` must be `true` for either trigger to
publish images and deploy; when disabled, only the checks run.

When changing the GitHub environment from `development` to `production`, update
the AWS deploy role's trust policy to match `github-trust-policy.json`. The OIDC
subject includes the environment name, so changing the workflow alone is not
enough to grant AWS access. Configure `AWS_REGION`, `AWS_DEPLOY_ROLE_ARN`, and
`EC2_INSTANCE_ID` on the `production` environment.

## Operation and recovery

The server pulls both images before changing containers, waits for health checks,
and checks `/api/status` through Nginx. Failed SSM commands fail the Actions job.
Deployments are serialized in Actions and by a server lock. `current.env` records
the last successful release and `previous.env` its predecessor. A failed release
can leave changed containers running; these files are release records, not proof
of current container state. Inspect containers and logs before retrying.

There is no automatic rollback. To roll back, review migration compatibility and
invoke `/opt/theeverthread/deploy.sh` with the previous two image digests. Never delete
the database volume to fix a deployment. Use `docker compose` with the same
Compose and environment files to inspect `ps` and `logs --tail 100`.
PostgreSQL is hosted in Neon; configure and rehearse database recovery there.
The frontend publishes HTTP port 80 on the host; restrict ingress to your public IP
for temporary testing. HTTPS is needed before real user data.

AWS uses temporary OIDC credentials ([GitHub documentation](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws)).
Compose readiness uses [`up --wait`](https://docs.docker.com/reference/cli/docker/compose/up/).

Architecture baseline: `ab3aa138af3f1b0d23d12661cc1c5849ab54a5ab`.
Remote `origin/main` freshness was verified before publishing these changes.
The development topology is proposed; this is not a production hosting decision.
