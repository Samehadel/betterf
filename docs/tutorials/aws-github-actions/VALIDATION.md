# Deployment and tutorial validation

Updated 27 September 2026, Africa/Cairo. The deployment completed on 26 September UTC. This record distinguishes evidence from optional exercises.

## Verified live workflow

[Run 36277636086](https://github.com/Samehadel/betterf/actions/runs/36277636086), source commit `d7157b7249e1f115559430d4570e00c401b04449`, was inspected through GitHub CLI after completion.

| Job | Result | What this establishes |
|---|---|---|
| verify | Success | Backend checks/JAR build; frontend tests/typecheck/build; browser checks; artifact uploads completed |
| publish | Success | Both linux/amd64 app images were built and pushed to GHCR |
| deploy | Success | AWS credential configuration and SSM-driven application deployment completed successfully |

The deployment command runs Compose's health wait and the HTTP status request before returning success. This is an end-to-end first deployment result, not a general proof of all application features or future releases.

The GitHub environment was separately read through the API: it contained the correct region, role ARN and instance ID, and allowed only the `develop` branch. The repository switch was read as `true` before manual dispatch. The repository's OIDC API confirmed its immutable subject prefix.

## Confirmed by the user during setup

- Session Manager terminal available on Ubuntu 24.04.4 LTS, x86_64.
- Docker hello-world succeeded; Compose reported v5.5.1.
- Password file existed with root ownership, mode 600 and 77 bytes.
- Compose configuration validation and deployment script syntax validation succeeded.
- EC2 Docker login to GHCR succeeded.
- IAM provider, deployment role and inline policy were created.
- After deployment, the user reported that the health checks and UI worked.

These confirmations are not an independent audit of every AWS console setting. The public IP, exact instance size, disk settings, budget and MFA status were not independently inspected for this tutorial.

## Tutorial consistency checks

Completed tutorial checks: **35 local links**, **38 Bash snippets** (with the conditional code excerpt closed for syntax checking), and **2 deliberately incomplete interactive heredoc starters**. Workflow YAML parsed and Compose configuration validation passed. The review covered:

- Local Markdown links resolving to files, and internal heading anchors resolving.
- Balanced code fences and syntax of complete Bash examples; the two documented interactive heredoc starters are intentionally incomplete commands.
- JSON policy parsing, Python helper syntax, and Compose's own configuration validation using dummy values.
- Snapshot copies matching `.github/workflows/ci.yml` and `deploy/aws/` exactly.
- Documented instance, region, repository identity and deployment switch matching the live configuration we used.
- No embedded actual token or password values; examples refer to protected files and interactive prompts.

Only documentation and learning snapshots were edited for the tutorial. No new deployment, credential rotation, migration or infrastructure change was required to write it.

## Earlier local implementation checks

Before the first live deployment, shell/Python/JSON/Compose syntax checks passed. Mocked SSM helper execution confirmed successful commands exit zero and failed/timed-out command results exit unsuccessfully. The live run subsequently verified the real successful path.

## Not tested or implemented

- Database restore, image rollback, migration rollback, disaster recovery or failover.
- Off-host backup scheduling, retention or monitoring.
- HTTPS, custom DNS, production authentication or public production readiness.
- Load/capacity testing, resource-cost optimization or high availability.
- Exhaustive IAM security review or concurrent deployment fault injection.
- A second visible application change solely as a continuous-delivery exercise.

Those topics are identified as future work or deliberate exercises. Do not interpret the tutorial's example recovery commands as a completed restore rehearsal.

Architecture reference: `ab3aa138af3f1b0d23d12661cc1c5849ab54a5ab`; remote `origin/main` was fetched successfully before the pipeline was pushed.

## Subsequent local versioning improvement

After the first deployment, root `VERSION` was introduced with value `0.1.0`,
frontend metadata was synchronized, and the executable JAR filename became
`app.jar`. The tutorial and workflow snapshot now reflect this change.

Local verification passed: five Node versioning tests, metadata consistency,
`./gradlew bootJar --no-daemon`, executable JAR manifest inspection with
`Implementation-Version: 0.1.0`, workflow YAML parsing, local links and shell
example syntax. This update has not yet been committed, pushed or deployed;
the original successful deployment is evidence for the earlier baseline.
