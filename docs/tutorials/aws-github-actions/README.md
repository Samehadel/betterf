# BetterF: your first AWS deployment pipeline

This is a guided development-environment tutorial, from an empty AWS account to a working GitHub Actions deployment of Angular, Spring Boot, and PostgreSQL. Each stage explains the new concepts before you use them. For a new installation, perform stages in order and stop at a failed checkpoint; for our existing installation, read without repeating completed setup.

This edition documents the deployment we completed on **27 September 2026, Cairo time** (26 September UTC). The [first deployment run](https://github.com/Samehadel/betterf/actions/runs/36277636086) passed `verify`, `publish`, and `deploy` for commit `d7157b7249e1f115559430d4570e00c401b04449`. You also confirmed the health checks and browser UI worked. Account creation, instance sizing, and backup arrangements were not independently audited; sections describing those are setup guidance, not claims that we performed them.

The application is already deployed. Read this as a reconstruction and reference; you do not need to recreate roles, regenerate secrets, or relaunch an instance to learn from it.

A subsequent local versioning improvement replaces the initial versioned JAR
filename with `app.jar` and introduces root `VERSION`. The code examples below
reflect that improvement; the linked first deployment run predates it. Read the
[versioning workflow](../../../architecture/versioning.md) before preparing named releases.

## How to study this tutorial

| Read | What you learn |
|---|---|
| This walkthrough, stages 1–5 | AWS account, server, networking, IAM, Session Manager and Docker |
| This walkthrough, stages 6–11 | Packaging, runtime configuration, registries, OIDC and pipeline activation |
| [Code explained](CODE-WALKTHROUGH.md) | The actual workflow, Dockerfiles, Nginx, Compose, Bash and Python, with code beside explanations |
| [Operations and command reference](OPERATIONS.md) | Health checks, logs, identifying a release, rollback, backups and command syntax |
| [Validation record](VALIDATION.md) | What actually passed and what has not been tested |

Open the linked source files while reading. Paths in commands are relative to the **Git repository root** unless a command explicitly enters another directory. Commands labelled **Mac** run on your computer; commands labelled **EC2** run inside Session Manager. Code labelled **runner** is executed by GitHub, not typed into EC2.

## Our actual environment

| Item | Value |
|---|---|
| Repository | `Samehadel/betterf` |
| Local repository root | `/Users/samehadel/Sameh/Projects/betterf` |
| Deployment branch / GitHub environment | `develop` / `development` |
| AWS account / region | `975050244178` / `us-east-1` (N. Virginia) |
| EC2 instance | `i-0c9b639e597170580` |
| Observed OS / CPU | Ubuntu 24.04.4 LTS / `x86_64` |
| Observed Compose version | `v5.5.1` |
| AWS deployment role | `arn:aws:iam::975050244178:role/betterf-github-deploy` |
| Server deployment directory | `/opt/betterf` |
| Images | `ghcr.io/samehadel/betterf-backend` and `ghcr.io/samehadel/betterf-frontend` |

Account and instance identifiers are not credentials. Passwords and tokens are deliberately absent. For a different account or repository, substitute its identifiers and verify its OIDC subject.

## Where every file lives

```text
betterf/                              ← Git repository root on your Mac / runner
├── .github/workflows/ci.yml           ← active workflow GitHub discovers
├── app/
│   ├── backend/                      ← Gradle + Spring Boot source
│   ├── frontend/                     ← npm + Angular source
│   └── compose.yaml                  ← local/CI PostgreSQL only
├── deploy/aws/
│   ├── backend.Dockerfile            ← packages the tested JAR
│   ├── frontend.Dockerfile           ← packages the compiled Angular website
│   ├── nginx.conf                    ← static site + API proxy
│   ├── compose.yaml                  ← EC2 three-service configuration
│   ├── deploy.sh                     ← installed on EC2, runs the release
│   ├── send-deployment.py             ← runs on the GitHub runner, calls AWS
│   ├── github-trust-policy.json       ← who may assume the AWS role
│   └── github-permissions-policy.json ← what that role may do
└── docs/tutorials/aws-github-actions/ ← this course; not runtime input
```

The two Compose files have different purposes. CI uses `app/compose.yaml` for its disposable database; EC2 uses `deploy/aws/compose.yaml` for the complete application.

The canonical deployment files are in [`deploy/aws`](../../../deploy/aws/README.md) and the [active workflow](../../../.github/workflows/ci.yml). The `examples/` directory is a snapshot for learning, synchronized to the current local workflow, including the versioning improvement. The live workflow never reads those example copies.

## CI, delivery and deployment

**Continuous integration (CI)** checks that a change builds and behaves as expected when combined with the project. Our `verify` job is CI: it builds, tests, type-checks and runs browser checks.

**Continuous delivery** keeps a checked release ready to deploy. **Continuous deployment** automatically sends an eligible checked release to an environment. With `AWS_DEPLOY_ENABLED=true`, a successful push to `develop` proceeds through publishing and deployment automatically. A manual trigger starts the same process without a new commit.

**Build** turns source into a JAR or compiled website. **Package** puts those outputs into runtime images. **Publish** uploads the images to GHCR. **Deploy** makes EC2 run the selected images. **Provision** prepares the server, networking and identities; we performed that setup separately through the AWS console and EC2 terminal.

The first deployment included one-time preparation. Later code releases reuse Docker, IAM roles, registry login, the installed script, and the persistent database. You do not repeat the whole setup for every change.

## What you will build

```text
Your computer: change code and push to GitHub
                         |
                         v
GitHub Actions: test -> build -> package -> publish images
                         |                    |
                         |                    v
                         |              GitHub Container Registry
                         |                    |
                         v                    | pull exact image versions
AWS OIDC role -> Systems Manager Run Command  |
                         |                    |
                         v                    v
                 One AWS EC2 development server
                 Docker Compose manages:
                 +----------------------------------+
Browser --HTTP--> | Nginx + compiled Angular          |
                 |       /api/* -> Spring Boot       |
                 |                    -> PostgreSQL  |
                 +---------------------------|------+
                                       persistent disk
```

The intended first result is an HTTP development site with ingress restricted to your own public IP. Do not use this stage for passwords or real customer data. HTTPS and a domain are the next delivery milestone, described at the end.

| Component | Meaning | Provider / choice |
|---|---|---|
| Source repository | Version history of your code | GitHub |
| CI | Automated build and checks | GitHub Actions |
| CD | Automated delivery after checks pass | GitHub Actions + AWS Systems Manager |
| Registry | Stores packaged application images | GitHub Container Registry (GHCR) |
| Server | Runs the application continuously | AWS EC2 |
| Server disk | Stores OS, images, and database files | AWS EBS |
| Container runtime | Starts isolated application processes | Docker Engine |
| Container configuration | Defines the three containers together | Docker Compose |
| Web server / reverse proxy | Serves Angular files and forwards API requests | Nginx |
| API | Executes business logic | Spring Boot / Java 25 |
| Database | Stores application data | PostgreSQL |
| Deployment authentication | Grants temporary AWS access to GitHub | OIDC + AWS IAM role |

There is no S3 bucket, RDS database, ECS cluster, Kubernetes cluster, or load balancer in this tutorial. EC2 still needs ordinary networking and disk resources; these are part of running a server.

### How this fits the existing project

The repository already has Gradle, Angular builds, PostgreSQL configuration, Liquibase migrations, health endpoints, and CI in `.github/workflows/ci.yml`. We preserve those checks and add packaging and deployment afterward. We do not implement business authentication or change Spring Security rules.

This tutorial uses `develop` as the deployment branch and `development` as the GitHub environment name. Those are explicit tutorial choices. If your deployment branch differs, change both the workflow condition and the GitHub environment branch restriction.

The initial deployment used commit `d7157b7`; with the subsequent versioning improvement the build uses: Java 25, the executable JAR `app.jar`, and Angular's `dist/frontend/browser/` output. Release version changes no longer change the artifact path. If output directories change, update the workflow paths accordingly.

## Stage 1 — Create an account and understand AWS identity

**Concept: AWS account.** An account contains your cloud resources and billing. The root login is the account owner's most powerful identity. Use it to establish the account, then use a separate identity for daily administration.

1. Register through [AWS](https://aws.amazon.com/). Complete email, contact, payment, and verification steps.
2. Set a unique password and enable root multi-factor authentication (MFA). MFA requires an additional proof, such as an authenticator or security key, beyond the password.
3. Open **IAM Identity Center**. Enable an **organization instance**, creating an AWS Organization if the console requests it. Enable management of AWS account access when offered.
4. Create your human user, configure MFA, create an administrator permission set for this learning account, and assign your user to the account with that permission set.
5. Use the AWS access portal URL provided by Identity Center to sign in as that user. Keep root for account-owner tasks. Do not create root access keys.

**Concept: IAM.** AWS Identity and Access Management controls who can act on AWS resources. A *policy* specifies allowed operations; a *role* is an identity that can be assumed temporarily; a *permission set* defines the access Identity Center gives your human user.

Your administrator access is intentionally broad for initial setup. The deployment role we create later will be narrower. This AWS IAM setup does not add login to BetterF; Cognito/Keycloak and your application's users are a separate topic.

Checkpoint: you can enter the AWS console through your access portal rather than root.

References: [AWS IAM security practices](https://docs.aws.amazon.com/IAM/latest/UserGuide/best-practices.html), [Identity Center getting started](https://docs.aws.amazon.com/singlesignon/latest/userguide/getting-started.html).

## Stage 2 — Understand costs and choose a region

**Concept: usage billing.** A running server costs money even while idle. Disk storage, public IPv4 addresses, and some network traffic can also be charged. A billing alert tells you about spending; it does not impose a hard spending cap. Account credits and free-tier terms depend on your account.

Open **Billing and Cost Management → Budgets → Create budget**. Create a monthly cost budget for an amount you are comfortable spending. Set email notifications at 50%, 80%, and 100%. Check your billing dashboard and any credits before launching resources. If desired, add a forecast alert as well.

**Concept: region.** A region is an AWS geographic operating area. Most resources in this tutorial exist in one region. An *Availability Zone* is an isolated location within that region. You do not need multiple zones for this first single-server exercise.

Our instance uses **US East (N. Virginia), `us-east-1`**. Keep EC2 and Systems Manager in that region. This is a learning choice, not a production residency decision.

Write down these values privately as you create them:

| Value | Example / purpose |
|---|---|
| AWS account ID | 12 digits, used in role identifiers |
| AWS region | `us-east-1` |
| GitHub owner/repository | `Samehadel/betterf`, preserve actual casing in trust policy |
| EC2 instance ID | `i-...`, obtained after launch |
| Public IPv4 | Used for your first browser visit |

These identifiers are not passwords. Never put actual credentials in Git, screenshots, or pipeline output.

Checkpoint: your budget exists and you know which region you are using.

Reference: [Create an AWS cost budget](https://docs.aws.amazon.com/cost-management/latest/userguide/create-cost-budget.html).

## Stage 3 — Give the future server its AWS identity

**Concept: Systems Manager and its agent.** Systems Manager is an AWS service that can administer a server. A small program called the SSM Agent runs on the server and communicates outward to AWS. *Session Manager* gives you an interactive terminal; *Run Command* executes an automated command and records the result.

We use these instead of opening SSH port 22. They do not require an S3 bucket for the command flow used here.

Create the instance role in **IAM → Roles → Create role**:

1. Choose trusted entity **AWS service**, use case **EC2**.
2. Attach `AmazonSSMManagedInstanceCore`.
3. Name the role `betterf-ec2-role`.

**Concept: instance profile.** This is how EC2 attaches a role to a server. The console normally creates the profile with your EC2 role. The server obtains temporary AWS credentials through that profile; you do not place access keys on disk.

This role lets the server communicate with Systems Manager. It does not grant GitHub permission to deploy. We create GitHub's separate role in Stage 9.

Reference: [Systems Manager instance permissions](https://docs.aws.amazon.com/systems-manager/latest/userguide/setup-instance-permissions.html).

## Stage 4 — Launch EC2 and understand its network

**Concept: EC2.** Elastic Compute Cloud rents you a virtual computer. An *instance* is one running virtual computer. You choose its operating system, CPU/memory capacity, disk, and network access. You are responsible for updating its OS and managing the software you install.

**Concept: AMI.** An Amazon Machine Image is the starting operating-system image. This tutorial uses **Canonical Ubuntu Server 24.04 LTS, x86_64**, so we can install Docker and Compose from Docker's official Ubuntu package repository. The commands below are for Ubuntu, not Amazon Linux.

We started with an already-running instance. For a future fresh setup, the following are suggested settings; the instance type, disk size and VPC below were not verified on our existing server. Open **EC2 → Instances → Launch instances**:

| Setting | Choose | Why |
|---|---|---|
| Name | `betterf-dev` | Recognizable development resource |
| AMI | Official Canonical Ubuntu Server 24.04 LTS | Supported Linux distribution |
| Architecture | x86_64 | Matches `linux/amd64` images built below |
| Instance type | `t3.medium` | Starting estimate: 2 vCPU, 4 GiB RAM for three containers |
| Key pair | Proceed without a key pair | We connect with Session Manager |
| VPC | Default VPC | Existing basic network for the exercise |
| Subnet | A default public subnet | Direct outbound internet connectivity |
| Auto-assign public IPv4 | Enabled | Package downloads, registry access, and development site |
| Security group | Create `betterf-dev-sg` | Instance network access rules |
| Inbound rules | Remove the suggested SSH rule; start empty | Session Manager needs no inbound SSH |
| Outbound rules | Default allowed outbound traffic | SSM and image/package downloads |
| Storage | 30 GiB gp3, encrypted | OS, container images, and PostgreSQL data |
| Advanced → IAM instance profile | `betterf-ec2-role` | Server's Systems Manager permissions |
| Metadata options | Require IMDSv2 | Modern instance credential metadata protocol |

**Concept: VPC, subnet, gateway, security group.** A VPC is your isolated AWS network. A subnet is a portion of it. A public subnet has a route to an internet gateway; a public IP plus that route enables internet connectivity. A security group is a stateful network firewall attached to the instance. These settings work together—having a public IP alone is not enough.

If your account lacks a default VPC, stop here and create/restore a default VPC through the VPC console or follow a dedicated network setup. Do not choose a private subnet and expect the same instructions to work. This tutorial does not require a NAT gateway.

**Concept: EBS.** Elastic Block Store provides the disk attached to EC2. gp3 is a general-purpose SSD option. Encryption protects stored disk data. A Docker volume will later store PostgreSQL files on this disk.

Review charges and launch. Wait for the instance to be running and its status checks to pass. Note its instance ID and public IPv4 address.

Select **Connect → Session Manager → Connect**, then run:

```bash
whoami
cat /etc/os-release
uname -m
```

The shell should show Ubuntu. If Session Manager is unavailable, check the region, role/profile, outbound internet route, and agent readiness. Supported Ubuntu images commonly have the agent preinstalled. If yours does not, see the agent installation guide; do not install a duplicate snap/deb agent.

If the existing instance has no role, open **Actions → Security → Modify IAM role** and attach the EC2 role from stage 3. If a role already exists, preserve it and check its SSM permissions. Wait a few minutes and retry **Connect → Session Manager**. Do not replace a role used by other workloads without reviewing its purpose.

Checkpoint: Session Manager opens a terminal, `cat /etc/os-release` identifies Ubuntu 24.04, and `uname -m` prints `x86_64`. These checks passed in our session.

References: [EC2 getting started](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EC2_GetStarted.html), [EC2 Session Manager connection](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/connect-with-systems-manager-session-manager.html), [Ubuntu SSM Agent](https://docs.aws.amazon.com/systems-manager/latest/userguide/agent-install-ubuntu.html).

## Stage 5 — Install Docker and Compose on EC2

**Concept: image versus container.** An image is a packaged filesystem and startup instructions. A container is a running instance of an image. Containers share the host's kernel but have their own process/network environment. Deleting a container should not delete your database; persistent data belongs in a volume.

**Concept: Docker Compose.** Compose reads a YAML file describing several containers, their environment variables, network connections, volumes, and health checks. It is sufficient for one server; it is not a multi-server scheduler.

Run the following **in the EC2 Session Manager terminal**, not on your Mac. This is for a fresh Ubuntu server with no existing Docker installation:

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

sudo tee /etc/apt/sources.list.d/docker.sources > /dev/null <<'EOF'
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
EOF

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker run --rm hello-world
sudo docker compose version
```

The package repository supplies Docker updates through `apt`. The `sudo` prefix runs commands as administrator. Docker access is powerful; we use `sudo` explicitly rather than giving all users access to its socket.

`apt-get update` refreshes package information; it does not upgrade installed packages. The first call prepares ordinary dependencies, and the second discovers packages in the newly added Docker repository. The signing key verifies repository metadata. `noble` selects Ubuntu 24.04 and `amd64` matches our CPU. `systemctl enable --now` starts Docker and arranges startup at boot. `hello-world` verifies download and execution, while `--rm` removes its finished container.

Checkpoint: we saw **Hello from Docker!** and **Docker Compose version v5.5.1**. See the [command dictionary](OPERATIONS.md#9-command-dictionary) for the flags and shell punctuation.

Reference: [Docker Engine on Ubuntu](https://docs.docker.com/engine/install/ubuntu/).

## Stage 6 — Understand and prepare container packaging

Do this stage **in the local Git repository**. Do not replace the application or install Node/Java on EC2. GitHub builds your application; EC2 runs the resulting packages.

**Concept: Dockerfile and build context.** A Dockerfile describes an image. Its build context is the directory of files Docker may copy into that image. We use deliberately small contexts containing only the tested JAR or frontend output. This keeps source files, `.env`, and credentials out of images.

The live packaging files are:

- [Backend Dockerfile](../../../deploy/aws/backend.Dockerfile): Java 25 runtime, an unprivileged application user, `curl` for readiness checks, and the tested executable JAR.
- [Frontend Dockerfile](../../../deploy/aws/frontend.Dockerfile): Nginx plus compiled Angular HTML, JavaScript, and CSS. Node is needed for the build, not to serve this static Angular application.
- [Nginx configuration](../../../deploy/aws/nginx.conf): serves the frontend and sends `/api/` requests to `backend:8080`. Angular deep links fall back to `index.html`.

**Concept: reverse proxy and same origin.** Your browser calls `http://SERVER/api/status`. Nginx receives that request and forwards it to Spring Boot inside Docker. Both frontend and API use the same browser origin, so this deployment does not need a separate cross-origin API URL. Never put a Docker hostname such as `backend` into browser-side configuration; only containers can resolve it.

Nginx uses Docker DNS to re-resolve `backend` when the backend container is replaced. This prevents the proxy from holding an old container IP. Actuator endpoints remain internal.

**Concept: artifacts, tags, and digests.** CI artifacts are intermediate build files passed between jobs. A registry stores runnable images. We tag images with the Git commit SHA for traceability, but deploy their SHA-256 *digests*: a digest identifies exact image content even if a tag later moves.

The runtime base image tags are readable tutorial defaults, not permanent reproducibility guarantees. Pin reviewed base-image digests and action commit SHAs as a later hardening step. PostgreSQL 17.6 matches the existing local foundation; review current security patch releases before real use and deliberately test upgrades.

Optional local packaging check, after running the existing build/tests:

```bash
# From the repository root. "package" contains generated files only.
mkdir -p /tmp/betterf-tutorial-package/backend /tmp/betterf-tutorial-package/frontend/browser
cp app/backend/build/libs/app.jar /tmp/betterf-tutorial-package/backend/app.jar
cp deploy/aws/backend.Dockerfile /tmp/betterf-tutorial-package/backend/Dockerfile
cp -R app/frontend/dist/frontend/browser/. /tmp/betterf-tutorial-package/frontend/browser/
cp deploy/aws/frontend.Dockerfile /tmp/betterf-tutorial-package/frontend/Dockerfile
cp deploy/aws/nginx.conf /tmp/betterf-tutorial-package/frontend/nginx.conf
docker build --platform linux/amd64 -t betterf-backend:test /tmp/betterf-tutorial-package/backend
docker build --platform linux/amd64 -t betterf-frontend:test /tmp/betterf-tutorial-package/frontend
```

Apple Silicon uses ARM natively; the explicit AMD64 platform matches the chosen EC2 instance. Emulation can make local builds slower.

## Stage 7 — Configure the three services and database storage

Read [compose.yaml](../../../deploy/aws/compose.yaml) before installing it.

**Concept: service discovery.** Compose creates a private network and DNS entries named after services. Spring connects to `postgres:5432`. Inside a backend container, `localhost` means that backend container, not the PostgreSQL container.

**Concept: published port.** Only Nginx publishes a host port (`80:80`). PostgreSQL 5432 and Spring Boot 8080 are accessible within the Compose network, but not published on EC2. `EXPOSE` in a Dockerfile is descriptive metadata; it does not open a host port.

The backend's `SERVER_ADDRESS=0.0.0.0` is needed so Nginx can reach it inside Docker. It does not publish the backend to the internet.

**Concept: volume.** `postgres-data` is a named Docker volume. Replacing the PostgreSQL container leaves the volume intact. Here its files live on the EC2 disk; losing that disk can lose the database. Persistence is not a backup.

**Concept: readiness and migrations.** PostgreSQL must become ready before Spring starts. Spring's Liquibase migration step prepares the schema. Its readiness endpoint includes database connectivity. Nginx starts after the backend is healthy. `docker compose up --wait` waits for these checks. A running process alone does not prove the application can serve requests. [Compose startup behavior](https://docs.docker.com/compose/how-tos/startup-order/)

**On EC2**, create a directory readable only by root:

```bash
sudo install -d -m 700 /opt/betterf
```

Create the runtime password without displaying it (**EC2**):

```bash
sudo bash -c 'set -e; umask 077; if [ ! -e /opt/betterf/secrets.env ]; then password=$(openssl rand -hex 32); printf "DB_PASSWORD=%s\n" "$password" > /opt/betterf/secrets.env; fi'
sudo ls -l /opt/betterf/secrets.env
sudo grep -qE '^DB_PASSWORD=[0-9a-f]{64}$' /opt/betterf/secrets.env \
  && echo "Password file is valid."
```

`700` gives root directory access. `umask 077` makes the new file private. The existence check preserves an existing password. The expected listing is `-rw------- 1 root root 77 ... secrets.env`; the quiet `grep` checks the format without revealing the value. We initially got “No such file or directory”; rerunning this guarded creation command fixed it.

Now open the [live Compose file](../../../deploy/aws/compose.yaml) on your Mac. In EC2, enter this line, paste the **entire file contents**, and then enter `EOF` alone on a new line:

```bash
sudo tee /opt/betterf/compose.yaml > /dev/null <<'EOF'
```

Repeat using the entire [deployment script](../../../deploy/aws/deploy.sh):

```bash
sudo tee /opt/betterf/deploy.sh > /dev/null <<'EOF'
```

These are **interactive paste starters**, not complete standalone scripts. The quoted `EOF` preserves `$` variables in the pasted content. If the terminal displays `>`, it is still waiting for content or the final `EOF`; that is not a deployment error. There must be no spaces before the terminating `EOF`.

Validate the files (**EC2**):

```bash
sudo chmod 600 /opt/betterf/compose.yaml
sudo chmod 700 /opt/betterf/deploy.sh
sudo bash -n /opt/betterf/deploy.sh \
  && echo "Deployment script syntax is valid."
sudo env BACKEND_IMAGE=backend:check FRONTEND_IMAGE=frontend:check \
  docker compose --env-file /opt/betterf/secrets.env \
  -f /opt/betterf/compose.yaml config --quiet \
  && echo "Compose configuration is valid."
```

`bash -n` parses without executing. `config --quiet` validates without printing expanded secrets. The two image names are validation placeholders; no image is downloaded and no container starts. Both confirmations appeared in our session.

The server-side script and Compose file are installed manually. Future application releases use the installed copies; pushing changes to these two files does not update EC2. Copy reviewed updates before deploying code that depends on them.

**Concept: runtime secret.** A database password is supplied when containers run rather than baked into their image.

The same password is provided to PostgreSQL and Spring at runtime. This root-only file is a simple development approach; administrators and Docker still have access to it. Do not print expanded `docker compose config` output because it can expose the password. Later, use a managed secret service and a rotation process if required.

Do not regenerate this file after database initialization: changing `POSTGRES_PASSWORD` does not change a password already stored in PostgreSQL. Also do not use `docker compose down -v` on data you want to keep; `-v` removes named volumes.

Checkpoint: Compose, the deployment script, and the password file exist. Containers are not running yet because there are no published application images.

## Stage 8 — Prepare GitHub Container Registry access

**Concept: registry authentication.** Publishing images and pulling images are separate operations. A GitHub Actions job uses its short-lived `GITHUB_TOKEN` to publish to GHCR. A standalone EC2 host cannot reuse that token after the job ends.

For this tutorial, keep images private. In GitHub, create a **personal access token (classic)** with **`read:packages`** for a user who can read the packages. Set an expiration and authorize organization SSO if required. Avoid additional scopes unless your organization's policies explicitly require them.

Then, **on EC2**, log Docker in as root—the same user Run Command uses:

```bash
sudo docker login ghcr.io --username Samehadel
```

Paste the token at Docker's **Password:** prompt and press Enter. It will not echo on screen or be entered as a shell command. We received **Login Succeeded**. Using `sudo` stores the login for root, the user that runs deployments. Without a credential helper, Docker saves recoverable credentials in `/root/.docker/config.json`; protect that file and never paste it into chat or logs.

This GitHub token is a remaining registry credential. AWS deployment authentication will use OIDC and no saved AWS keys. If you deliberately choose public images, anonymous pulls can avoid a registry token, but do not publish private application code accidentally.

After the first image publication, confirm the package settings give this repository Actions access and your token's user read access. An existing package with the same name may need explicit access granted.

Reference: [GHCR authentication and permissions](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

## Stage 9 — Establish trust between GitHub and AWS

**Concept: OIDC.** OpenID Connect lets GitHub present a signed statement describing the workflow's identity. AWS checks that statement against a role's trust policy, then issues temporary credentials. GitHub does not need an AWS access-key secret.

**Concept: trust versus permissions.** A trust policy answers *who can assume the role?* A permissions policy answers *what can that role do?* Both are needed.

In AWS **IAM → Identity providers → Add provider**, select OpenID Connect:

| Field | Value |
|---|---|
| Provider URL | `https://token.actions.githubusercontent.com` |
| Audience | `sts.amazonaws.com` |

If that provider already exists, reuse it. In **IAM → Roles → Create role → Custom trust policy**, paste [github-trust-policy.json](../../../deploy/aws/github-trust-policy.json). Continue without attaching a broad managed policy, name the role `betterf-github-deploy`, and create it. An **ARN** identifies an AWS resource. Our provider ARN is `arn:aws:iam::975050244178:oidc-provider/token.actions.githubusercontent.com`.

Our repository uses GitHub's **immutable subject** format. We verified it from the API, not by guessing from the repository name (**Mac**, read-only):

```bash
gh api repos/Samehadel/betterf/actions/oidc/customization/sub
```

It returned `use_immutable_subject: true` and prefix `repo:Samehadel@24625713/betterf@1380628408`. The owner and repository IDs prevent a recreated namespace from having the same identity. Our full subject is:

```text
repo:Samehadel@24625713/betterf@1380628408:environment:development
```

This matches the workflow's `environment: development`. An environment-based OIDC subject is different from a branch-based subject. Do not combine the two formats.

Open the created role → **Permissions → Add permissions → Create inline policy → JSON**. Paste [github-permissions-policy.json](../../../deploy/aws/github-permissions-policy.json), then name it `betterf-deploy-permissions`. This file contains our exact account, region and instance. The AWS-managed SSM document ARN intentionally has an empty account field (`us-east-1::document/...`).

The policy permits sending shell commands only to this EC2 instance using `AWS-RunShellScript`, and reading command results. `GetCommandInvocation` uses `Resource: "*"` because that read operation does not provide instance-level resource scoping. The role can execute powerful commands on this one server, so repository write access and environment branch restrictions form part of your deployment security.

Record its ARN, for example:

```text
arn:aws:iam::975050244178:role/betterf-github-deploy
```

References: [GitHub OIDC with AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws), [Run Command permissions](https://docs.aws.amazon.com/systems-manager/latest/userguide/run-command-setting-up.html).

## Stage 10 — Configure the GitHub deployment environment

**Concept: environment.** A GitHub environment names a deployment destination and holds its variables and protection rules. It is not a server by itself.

Open the repository's **Settings → Environments → New environment**, name it **`development`**. Configure deployment branches/tags to allow only the **`develop` branch** (not a tag with that name). This matters because the AWS trust policy trusts the environment subject.

Feature availability can depend on your GitHub plan and repository visibility. Our repository supported the branch rule, and we verified it through GitHub’s API. If unavailable in another repository, redesign the trust and workflow together before enabling deployment; a branch-based subject must use that repository’s actual identity prefix.

Add these environment **variables**, not secrets:

| Name | Value |
|---|---|
| `AWS_REGION` | `us-east-1` |
| `AWS_DEPLOY_ROLE_ARN` | `arn:aws:iam::975050244178:role/betterf-github-deploy` |
| `EC2_INSTANCE_ID` | `i-0c9b639e597170580` |

No AWS access key is needed. No database password is placed in GitHub. `GITHUB_TOKEN` is supplied automatically to each job; you do not create it manually.

Protect `develop` using repository rules so ordinary changes pass checks and review before merge, where your GitHub plan supports this. Keep deployment changes subject to review too.

Checkpoint: environment name, trust subject, branch rules, account ID, and region agree.

## Stage 11 — Activate the complete pipeline

**Concept: workflow, job, runner, action.** A workflow is a YAML automation file. A job is a group of steps executed on a runner (a temporary machine). An action is a reusable step. Different jobs do not share a filesystem, so we transfer build artifacts explicitly.

Read the active [workflow](../../../.github/workflows/ci.yml). It preserves the existing project's checks and adds three behaviors:

1. **`verify`** builds and tests Spring/Angular, runs the browser checks against PostgreSQL, and uploads the tested JAR and browser files.
2. **`publish`** runs only on the deployment branch after verification, with the repository deployment switch enabled. It downloads those files, builds AMD64 runtime images, and pushes both to GHCR. Its outputs are exact image digests.
3. **`deploy`** assumes the AWS role using OIDC, sends one SSM command, and waits for the command result and application health.

`needs:` is the dependency between jobs. `packages: write` lets only the publishing job publish images. `id-token: write` lets only the deployment job request an OIDC token; it does not itself grant AWS permissions. Pull requests run checks without publishing or deploying. The workflow uses GitHub-hosted Ubuntu runners, which provide Docker and AWS CLI.

The Python [send-deployment.py](../../../deploy/aws/send-deployment.py) helper sends arguments as structured JSON, handles SSM's eventual consistency, and polls for terminal success/failure. A successful `send-command` API response means the request was accepted, not that your application deployed successfully. [AWS Run Command walkthrough](https://docs.aws.amazon.com/systems-manager/latest/userguide/walkthrough-cli.html)

Deployment concurrency prevents two workflow jobs from deploying simultaneously. A server-side file lock also protects against overlapping manual commands. Pending GitHub runs can be superseded; this is not a strict deployment queue. Avoid rerunning an old commit after a newer one unless intentionally restoring that version.

GitHub discovers `.github/workflows/ci.yml` at the repository root. The folder `deploy/` has no special meaning to GitHub: the YAML explicitly copies `deploy/aws/*.Dockerfile` and `nginx.conf`, and runs `python3 deploy/aws/send-deployment.py`. A workflow under `app/.github/workflows/` is not a root GitHub workflow in this repository.

**What we did on the Mac:** committed the workflow, deployment files, and application README as `d7157b7`, then pushed to `develop`. The first push was rejected because our GitHub CLI OAuth login lacked permission to update workflows. We fixed that with:

```bash
gh auth refresh -h github.com -s workflow
git push origin develop
```

Complete the browser authorization when prompted. This scope belongs to your human GitHub login; it is unrelated to AWS IAM or the workflow's `id-token: write` permission.

The first pushed run passed CI while deployment was disabled. Then in **GitHub → repository Settings → Secrets and variables → Actions → Variables → New repository variable**, we added:

| Name | Value | Scope |
|---|---|---|
| `AWS_DEPLOY_ENABLED` | `true` | Repository Actions variable |

This variable must be at repository scope: the `publish` job checks it before the `deploy` job enters the environment. It is a string comparison with lowercase `true`. Missing or different values skip publishing and consequently deployment. Setting it does not start a run.

We started the first deployment manually (**Mac**, this command starts a real deployment when the gate is enabled):

```bash
gh workflow run ci.yml --ref develop
```

This requests the `workflow_dispatch` event using `develop`. It does not create a commit. The workflow also runs on pushes and pull requests, but its publishing condition excludes pull requests and refs other than `develop`.

You can use **Actions → select the workflow → Run workflow → develop** instead. The manual-run UI depends on workflow availability on the default branch. Our run listing retained the older label **Foundation checks**; identify it by file `ci.yml`, branch, commit and jobs rather than the display name alone.

Read-only monitoring (**Mac**):

```bash
gh run list --branch develop --limit 5
gh run view 36277636086
gh run watch 36277636086 --exit-status
```

`--exit-status` makes the watcher fail if the run fails. Our [run 36277636086](https://github.com/Samehadel/betterf/actions/runs/36277636086) completed all three jobs successfully. For a later run, use its own numeric run ID.

Expected sequence on EC2:

```text
Pull backend/frontend images by digest
  -> start PostgreSQL and wait
  -> start Spring Boot, apply migrations, and wait
  -> start Nginx/Angular and wait
  -> GET /api/status through Nginx
  -> save successful image references in current.env
```

The first launch also downloads the PostgreSQL image. Future application deployments do not deliberately upgrade it. The script does not stop the entire stack first; it replaces changed services. This remains a single-server deployment and can have brief downtime—it is not blue/green or zero-downtime deployment.

Checkpoint: all three workflow jobs are green, and the final log contains `Deployment succeeded` and SSM status `Success`.

## Stage 12 — Visit the site and verify all three layers

**Concept: HTTP ingress.** Now that the services are healthy, allow your own browser to reach Nginx. In EC2, open the instance's security group and add an inbound rule:

| Type | Port | Source |
|---|---|---|
| HTTP | 80 | My IP (your public IPv4 `/32`) |

Do not open database port 5432 or backend port 8080. Your ISP/VPN can change your public IP; update this rule when that happens.

Visit `http://YOUR_PUBLIC_IPV4/` and then `http://YOUR_PUBLIC_IPV4/status`. The diagnostic page should report **Connected**. HTTP is temporary for non-sensitive development checks. Restricting the IP is not a substitute for TLS or application authentication.

In the EC2 terminal:

```bash
sudo bash
cd /opt/betterf
docker compose --env-file secrets.env --env-file current.env ps
docker compose --env-file secrets.env --env-file current.env exec -T backend \
  curl -fsS http://127.0.0.1:8080/actuator/health/readiness
curl --fail http://127.0.0.1/api/status
docker compose --env-file secrets.env --env-file current.env exec -T postgres \
  psql -U betterf -d betterf -c 'SELECT current_database();'
exit
```

`ps` should show three healthy services. `/api/status` should return the project's status envelope with `UP`; the SQL command should return `betterf`.

To prove continuous delivery, change a harmless visible heading in Angular, commit through your normal branch/review process, and merge to `develop`. Confirm the workflow succeeds and a fresh browser load shows the change. Review the published images' source commit and `/opt/betterf/current.env` to connect the deployment to the exact release.

In our session you confirmed these checks and the browser UI worked. You now have a working development pipeline: source changes are tested, packaged, published, deployed, and checked automatically.

## Stage 13 — Learn routine operations and recovery

**Logs.** On EC2, enter a root shell with `sudo bash`, then:

```bash
cd /opt/betterf
docker compose --env-file secrets.env --env-file current.env logs --tail 100 backend
docker compose --env-file secrets.env --env-file current.env logs --tail 100 frontend
docker compose --env-file secrets.env --env-file current.env logs --tail 100 postgres
df -h
docker system df
exit
```

After a failed deployment, use `candidate.env` if no successful `current.env` exists. `candidate.env` records what the failed attempt tried to launch; `current.env` records the last successful release, not a guarantee that every currently running container still matches it. Inspect `docker compose ps` when diagnosing failures.

Logs rotate to limit growth. Images still accumulate: inspect disk usage, retain images needed for recovery, and remove old unused images deliberately. Do not blindly prune volumes.

**Rollback.** A rollback runs a previously working version. The script keeps `previous.env` after a second successful deployment. After a failed attempt, `current.env` remains the last successful release. Choose the correct file, inspect its image references, and run:

```bash
sudo bash
cd /opt/betterf
# Use current.env to recover from a failed attempt.
# Use previous.env to undo the most recent successful deployment.
release_file=previous.env
backend_ref=$(sed -n 's/^BACKEND_IMAGE=//p' "$release_file")
frontend_ref=$(sed -n 's/^FRONTEND_IMAGE=//p' "$release_file")
/opt/betterf/deploy.sh "$backend_ref" "$frontend_ref"
exit
```

These files contain image references only, not credentials. The script validates digest references and checks application health again. On the first deployment there is no previous release to restore.

**Important database boundary:** image rollback does not undo Liquibase migrations or restore data. Check schema compatibility before rollback. For real releases, use backward-compatible migrations and a tested database recovery plan. This is why the tutorial does not automatically roll back images on every failure.

**Backups.** Before data matters, make a logical PostgreSQL backup and test restoring it to a separate database. This example creates a local backup on EC2:

```bash
sudo bash
set -euo pipefail
umask 077
cd /opt/betterf
mkdir -p backups
backup_file="backups/betterf-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose --env-file secrets.env --env-file current.env exec -T postgres \
  pg_dump -U betterf -d betterf -Fc > "$backup_file"
test -s "$backup_file"
exit
```

A file on the same disk does not protect against losing the server disk. Arrange a separate encrypted backup destination and retention before real data. S3 is one future option, not a requirement of this pipeline. Do not experiment with restoring over your active database.

**Stopping versus terminating.** Stopping an EC2 instance pauses it and normally retains its EBS disk. Storage charges remain, and the automatically assigned public IPv4 can change at the next start. Terminating deletes the instance; its root disk is usually deleted too, depending on its delete-on-termination setting. Inspect that setting before termination. Docker restart policies and the enabled Docker service bring containers back after a restart; health checks report failures but do not themselves guarantee automatic repair of an unhealthy process.

## Troubleshooting guide

| Symptom | First checks |
|---|---|
| Session Manager cannot connect | Correct region; instance profile attached; SSM Agent running; subnet internet route; outbound HTTPS |
| OIDC `AccessDenied` | Account ID, repo casing, audience, environment name, trust subject, and `id-token: write` |
| SSM `AccessDenied` | Instance/document ARNs in GitHub role; distinguish that role from the EC2 role |
| SSM `InvalidInstanceId` | Wrong region/account/instance, or instance not registered with Systems Manager |
| GHCR publication denied | `packages: write`; repository/package Actions access; organization policy |
| EC2 image pull denied | Run `docker login` as root; token expiration, `read:packages`, package read permission and SSO authorization |
| Missing frontend artifact | Build output must be `app/frontend/dist/frontend/browser`; check Angular config |
| Backend exits | Logs; Java version; DB URL; credential consistency; Liquibase migration failure |
| Database password fails after changing file | Existing database keeps its old password; use a deliberate database password-change process |
| Nginx 502 | Backend health, Docker DNS/network, `SERVER_ADDRESS=0.0.0.0` |
| Server-side curl works, browser fails | Public IP, HTTP security-group source, subnet route, browser forcing HTTPS |
| `/api/status` works but another API returns 403 | Existing Spring Security deliberately denies unimplemented routes; deployment does not change authorization |
| SSM accepted command but deploy failed | Read terminal command status and output; acceptance is not completion |
| Deployment says another is running | Check the active Actions/SSM command; do not run overlapping manual deployments |
| Timeout or full disk | Inspect logs, memory and disk; do not delete PostgreSQL volume to make space |

## Next deliveries, in a useful order

1. **Domain and HTTPS.** A domain maps a name to an address through DNS. A TLS certificate enables encrypted HTTPS. Stabilize the server address (for example, an Elastic IP, with its charges), create a DNS A record at your domain provider, and configure a certificate/renewal solution for Nginx or a TLS-capable reverse proxy. Open 443 as appropriate and redirect HTTP to HTTPS. Test renewal, redirects, and forwarded-header handling before adding login or real users. A domain can stay with your existing registrar; Route 53 is optional.
2. **Durable recovery.** Separate off-host database backups, restore rehearsals, documented disk recovery, and disk/cost alerts.
3. **Reproducibility and patching.** Pin reviewed image digests and action SHAs, automate dependency updates, scan images, and schedule OS/PostgreSQL patches.
4. **Stronger environment separation.** Separate development and production resources, identities, data, and deployment controls. Move from broad human administrator access to appropriate daily permissions.
5. **Infrastructure as code.** Terraform or CloudFormation can describe the server, role, and networking as versioned files after you understand their console equivalents.
6. **Scale only when needed.** Managed databases, additional servers, a load balancer, and orchestration can address availability or capacity requirements later.

## What was verified while writing this tutorial

The [validation record](VALIDATION.md) separates automated evidence from your EC2/browser confirmations and future exercises. All jobs of the live deployment passed; this is now a deployed development setup, not just a proposed tutorial example.

The architecture baseline consulted was `ab3aa138af3f1b0d23d12661cc1c5849ab54a5ab`; `origin/main` freshness was successfully verified before publishing the pipeline. Production hosting, HTTPS, backups and high availability are separate future work.

Continue with [Code explained](CODE-WALKTHROUGH.md), then use [Operations](OPERATIONS.md) beside your terminal.
