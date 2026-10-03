# Verify, operate and troubleshoot BetterF

[Setup walkthrough](README.md) · [Code explained](CODE-WALKTHROUGH.md)

Commands below are labelled **EC2** or **Mac**. Reading status does not redeploy the application. Commands that start a deployment or modify resources are explicitly identified.

## 1. Three places to look

| Place | What it tells you |
|---|---|
| GitHub Actions run | Did checks, packaging, AWS authentication and remote deployment succeed? |
| EC2 terminal | Which containers run now, their health, logs and release references |
| Browser | Can your network reach the site, render the UI and call the API? |

The first successful [deployment run](https://github.com/Samehadel/betterf/actions/runs/36277636086) used commit `d7157b7`. The user confirmed the UI and health checks worked. These are separate observations: a healthy localhost API does not prove the public security-group rule is correct.

## 2. Check the running application

**EC2 — Session Manager terminal:** define this shell function for the current session:

```bash
bf() {
  sudo docker compose \
    --env-file /opt/betterf/secrets.env \
    --env-file /opt/betterf/current.env \
    -f /opt/betterf/compose.yaml "$@"
}
```

`bf` is only a convenience function in your current shell. `"$@"` forwards each argument without losing its boundaries. It uses the absolute paths regardless of your current directory. Recreate it after reconnecting; it is not installed as an executable.

Now run:

```bash
bf ps
```

Expected: `postgres`, `backend`, and `frontend` running and healthy. `current.env` exists only after a successful deployment. If the first deployment failed, use `candidate.env` instead when defining the helper, after inspecting the failed run.

**EC2 — backend readiness, including database connectivity:**

```bash
bf exec -T backend curl -fsS \
  http://127.0.0.1:8080/actuator/health/readiness
```

Expected: JSON with `"status":"UP"`. `exec` runs a command inside the existing backend container, so `127.0.0.1:8080` refers to Spring there. `-T` disables a pseudo-terminal, which is convenient for plain command output.

**EC2 — backend liveness:**

```bash
bf exec -T backend curl -fsS \
  http://127.0.0.1:8080/actuator/health/liveness
```

Liveness concerns the process. Readiness in our application includes PostgreSQL. A live process can still be unready to serve requests.

**EC2 — frontend and API proxy:**

```bash
curl -I http://127.0.0.1/
curl -fsS http://127.0.0.1/api/status
```

`-I` requests response headers. The second request should return:

```json
{"data":{"status":"UP"},"error":null}
```

This goes through the published Nginx port and then to the backend. A host-side curl to `127.0.0.1:8080` is not the correct check because the backend port is not published on EC2.

**EC2 — optional read-only SQL check:**

```bash
bf exec -T postgres psql -U betterf -d betterf \
  -c 'SELECT current_database();'
```

Expected database name: `betterf`. This runs the PostgreSQL client inside the database container.

## 3. Open the website

In **AWS Console → EC2 → instance → Security → security group → Edit inbound rules**, allow **HTTP, port 80, source My IP**. Use your computer's public IP, not the instance's IP. Updating this rule changes network access; no container restart is needed.

Copy the instance's current **Public IPv4 address** and open:

```text
http://YOUR_PUBLIC_IP/
http://YOUR_PUBLIC_IP/status
```

The landing page should render; the diagnostic route should display **Connected**. We have not configured HTTPS, so explicitly use `http://`. Public access to `/actuator` returns 404 by design; use the internal checks above.

If you switch VPN or network, your public IP may change. Update the source rule. An automatically assigned EC2 public IP can also change after a stop/start, so check the instance's current address. Ports 8080 and 5432 do not need inbound rules.

## 4. Prove which release is running

**EC2 — inspect the successful release record:**

```bash
sudo cat /opt/betterf/current.env
```

This prints image references, not database credentials. Do not substitute `secrets.env` into that command.

**EC2 — inspect the configured image references of the actual containers:**

```bash
for service in backend frontend; do
  container_id=$(bf ps -q "$service")
  if [ -n "$container_id" ]; then
    sudo docker inspect --format '{{.Name}} {{.Config.Image}} {{.Image}}' "$container_id"
  fi
done
```

`.Config.Image` is the image reference used to create the container. Compare it with the corresponding digest in `current.env`. `.Image` is Docker's local image ID; it is not necessarily equal to the registry manifest digest. Printing selected fields avoids exposing runtime environment secrets through a full inspect dump.

Then open the GitHub run, check its source commit, and examine the publish outputs for the digests. Our images are also tagged with the source commit SHA. This gives a chain of evidence:

```text
GitHub source commit → published image digest → current.env → container image reference
```

`current.env` alone is not proof of current container state after a failed update, because a failed deployment can have partially replaced containers.

## 5. Read logs and resource usage

**EC2 — read-only diagnostics:**

```bash
bf logs --tail 100 backend
bf logs --tail 100 frontend
bf logs --tail 100 postgres
sudo docker stats --no-stream
free -h
df -h
sudo docker system df
```

| Command | What to look for |
|---|---|
| Backend logs | Startup errors, Liquibase failures, database connections |
| Frontend logs | Nginx startup errors and request/proxy failures |
| PostgreSQL logs | Authentication, initialization or disk issues |
| `docker stats --no-stream` | One sample of container CPU and memory use |
| `free -h` | Host memory availability |
| `df -h` | Filesystem free space |
| `docker system df` | Docker image, container and volume disk usage |

Use `bf logs --follow --tail 100 backend` to stream logs; Ctrl+C stops the log viewer, not the backend. Review logs before sharing them, since future application features might log sensitive values.

Logs rotate, but images can accumulate. Remove only reviewed unused images, retaining releases needed for recovery. Never use volume pruning as a generic disk-space fix.

## 6. Trigger and monitor another deployment

**Mac — repository directory:**

```bash
cd /Users/samehadel/Sameh/Projects/betterf
gh run list --branch develop --limit 5
```

To deliberately start another deployment of current `develop`:

```bash
gh workflow run ci.yml --ref develop
```

Find the new run's numeric ID in the list. Then, replacing `RUN_ID` with that number:

```bash
gh run view RUN_ID
gh run watch RUN_ID --exit-status
```

If it fails:

```bash
gh run view RUN_ID --log-failed
```

The workflow builds again and publishes new images, then sends their digests to EC2. An old run's “re-run” action uses that old run's commit; it can restore old application code. Choose the source revision deliberately.

For normal source changes, a push or merge to `develop` also starts the pipeline. Since `AWS_DEPLOY_ENABLED=true`, successful checks lead to deployment. The workflow has no path filters: even a documentation-only push to `develop` currently runs all jobs.

### Pause future automated releases

In **GitHub → Settings → Secrets and variables → Actions → Variables**, set repository variable `AWS_DEPLOY_ENABLED` to `false` (or remove it). CI still runs, but future publish jobs are skipped. This does not cancel an already-running release or stop the running app.

Our three target identifiers belong under **Settings → Environments → development → Environment variables**; the enable switch belongs under **repository Actions variables**. Neither location contains the database password.

## 7. Recover from a failed release

Read the failed Actions logs and SSM command ID first. A timeout on the runner does not necessarily terminate the remote command. Do not overlap retries while the old command is still running.

The release files have specific meanings:

| File on EC2 | Meaning |
|---|---|
| `candidate.env` | Most recent attempted references, if not promoted successfully |
| `current.env` | Last successful references |
| `previous.env` | Successful references replaced by the last successful deployment |
| `secrets.env` | Database password; independent of image versions |

Pull failures happen before container replacement. Health-check failures can happen after replacement. Diagnose actual container state rather than assuming either all old or all new containers are running.

### Manual image rollback: changes the running application

Use only after reviewing database migration compatibility and confirming no deployment is active. On the first successful release there is no previous release to restore.

**EC2:**

```bash
sudo bash
cd /opt/betterf
# previous.env undoes the latest successful release.
# current.env recovers the last success after a failed attempt.
release_file=previous.env
if [ -f "$release_file" ]; then
  backend_ref=$(sed -n 's/^BACKEND_IMAGE=//p' "$release_file")
  frontend_ref=$(sed -n 's/^FRONTEND_IMAGE=//p' "$release_file")
  /opt/betterf/deploy.sh "$backend_ref" "$frontend_ref"
else
  echo "No release file available; no deployment attempted."
fi
exit
```

The script validates the digests, pulls the images, waits for health and updates the release records. This action restores application images only. It does not restore database rows, reverse Liquibase migrations or recover a deleted volume.

### Back up before data matters

This optional exercise creates a **local** PostgreSQL dump; it was not executed as part of the first deployment.

**EC2:**

```bash
sudo bash
set -euo pipefail
umask 077
cd /opt/betterf
mkdir -p backups
backup_file="backups/betterf-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose --env-file secrets.env --env-file current.env \
  -f compose.yaml exec -T postgres pg_dump -U betterf -d betterf -Fc > "$backup_file"
test -s "$backup_file"
exit
```

A successful dump is a first step. Store backups encrypted away from the instance, define retention, and rehearse restoring into an isolated database. A same-disk dump is lost with the disk. This course does not claim backups, recovery or high availability have been implemented.

## 8. Troubleshooting by layer

| Symptom | Inspect / resolve |
|---|---|
| Heredoc paste shows `>` and no output | Enter its exact ending marker, e.g. `EOF`, alone on a new line |
| `secrets.env` missing | Run the guarded creation command from stage 7; verify with `ls -l` |
| Syntax check prints nothing | `bash -n` is silent on success; append `&& echo "Valid"` |
| Push rejected with missing `workflow` scope | On Mac run `gh auth refresh -h github.com -s workflow`, authorize, then retry push |
| Pipeline green but app unchanged | Check publish/deploy were not skipped; check branch and repository switch |
| OIDC role assumption denied | Exact immutable subject, audience, environment and provider ARN |
| SSM permission denied | Permissions policy instance/document ARNs; the deployment role differs from the EC2 role |
| SSM instance unavailable | Instance running in us-east-1, agent online, instance role and outbound connectivity |
| Image pull denied | Root's GHCR login, classic token expiration/read scope, package read access |
| Backend readiness fails | Backend/PostgreSQL logs, password consistency, migrations and memory |
| Nginx returns 502 | Backend health, Compose DNS, backend listener on `0.0.0.0` |
| Deep link fails | Correct `nginx.conf` image with index fallback |
| Local API works, browser does not | Public IP, security-group source, HTTP vs HTTPS, browser/network behavior |
| Public actuator returns 404 | Expected; check from inside backend container |
| Some APIs return 403 | Existing application security rules; deployment does not add business authorization |
| Disk full | Inspect logs, images and database growth; do not delete persistent volumes blindly |
| Restart fails after changing password file | PostgreSQL's existing password does not change just because env changes |

## 9. Command dictionary

These are the building blocks used during our setup.

| Syntax | Meaning |
|---|---|
| `sudo` | Runs with administrator permissions |
| `apt-get update` | Refreshes available-package information |
| `apt-get install -y ...` | Installs named packages and answers installation confirmation |
| `install -d -m 700 PATH` | Creates a directory with owner-only access |
| `chmod 600 FILE` | Owner can read/write; nobody else has permissions |
| `chmod 700 FILE` | Owner can read/write/execute |
| `chmod a+r FILE` | Adds read permission for everyone |
| `umask 077` | Removes group/other permissions from newly created files |
| `openssl rand -hex 32` | Generates 32 random bytes encoded as 64 hex characters |
| `tee FILE` | Writes incoming text to a file and standard output |
| `> /dev/null` | Discards normal output |
| `> FILE` / `>> FILE` | Overwrites / appends output |
| `2>&1` | Sends error output to the normal output destination |
| `<<'EOF'` | Reads literal multi-line input up to a line containing only `EOF` |
| `\` at line end | Continues the same shell command on the next line |
| `&&` | Runs the next command only if the previous succeeded |
| `||` | Runs the next command only if the previous failed |
| `$?` | Exit status of the preceding command; zero normally means success |
| `$(command)` | Captures a command's output for use as a value |
| `set -e` / `-u` / `-o pipefail` | Common error handling: command failure, unset variables, pipeline failure |
| `curl -fsS` | Fails on HTTP errors, hides progress, shows errors |
| `curl -L` / `-o FILE` | Follows redirects / writes downloaded output to a file |
| `grep -qE PATTERN FILE` | Checks an extended pattern without printing matching content |
| `bash -n FILE` | Checks shell syntax without executing |
| `docker compose -f FILE` | Chooses the Compose definition |
| `--env-file FILE` | Supplies variables for Compose configuration interpolation |
| `docker compose config --quiet` | Validates without displaying expanded values |
| `up -d --wait` | Starts/updates containers and waits for health |
| `exec -T SERVICE ...` | Executes inside an existing container without allocating a terminal |
| `docker run --rm IMAGE` | Creates/runs a container, removes it when it exits |
| `systemctl enable --now docker` | Starts Docker now and enables startup at boot |
| `gh workflow run ci.yml --ref develop` | Requests a manual workflow run on develop |

Avoid using the expanded `docker compose config` or unfiltered `docker inspect` output as a shareable diagnostic: they can contain runtime secrets. Quiet validation and selected inspect fields are enough for the checks in this guide.

## 10. Practice without changing the live app

1. Find the `context:` fields in the workflow and identify which files can enter each image.
2. Draw the browser → Nginx → backend → database request path, labelling ports and hostnames.
3. List the three environment variables and explain why the enable switch has a different scope.
4. Read `current.env` and compare its image references to the running containers.
5. Read the completed Actions run and distinguish the Git commit SHA, image digest and SSM command ID.
6. Explain why an updated IAM JSON file or Compose file in Git is not automatically applied to AWS/EC2.

For a later controlled exercise, make a harmless visible UI change through your normal review process, deploy it, and verify the new source-to-image-to-browser chain. This is optional and was not performed just to write the tutorial.
