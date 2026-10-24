#!/usr/bin/env bash
# One-shot EC2 setup for theeverthread (Ubuntu 24.04, x86_64).
# Does PDF stages 5 and 7: installs Docker + Compose, creates /opt/theeverthread,
# generates the DB password (only if missing), writes compose.yaml and deploy.sh,
# sets permissions, and validates everything.
#
# Safe to re-run: Docker is skipped if present, secrets.env is never regenerated,
# compose.yaml and deploy.sh are rewritten from this script.
#
# Run on the EC2 instance:  sudo bash setup-theeverthread.sh
set -Eeuo pipefail

APP_DIR=/opt/theeverthread

if [[ $EUID -ne 0 ]]; then
  echo "Run as root: sudo bash $0" >&2
  exit 1
fi

. /etc/os-release
if [[ "${ID}" != "ubuntu" ]]; then
  echo "This script is for Ubuntu; found ${PRETTY_NAME}." >&2
  exit 1
fi
if [[ "$(dpkg --print-architecture)" != "amd64" ]]; then
  echo "This script expects an amd64 (x86_64) instance." >&2
  exit 1
fi

echo "==> [1/5] Installing Docker Engine and Compose plugin"
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl openssl

if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  echo "Docker and Compose already installed; skipping."
else
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc

  cat > /etc/apt/sources.list.d/docker.sources <<DOCKER_EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${VERSION_CODENAME}
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
DOCKER_EOF

  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker
docker run --rm hello-world >/dev/null && echo "Docker works."
docker compose version

echo "==> [2/5] Creating ${APP_DIR}"
install -d -m 700 "${APP_DIR}"

echo "==> [3/5] Database password"
if [[ ! -e "${APP_DIR}/secrets.env" ]]; then
  ( umask 077; printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 32)" > "${APP_DIR}/secrets.env" )
  echo "Created secrets.env."
else
  echo "secrets.env already exists; keeping the existing password."
fi
chmod 600 "${APP_DIR}/secrets.env"
grep -qE '^DB_PASSWORD=[0-9a-f]{64}$' "${APP_DIR}/secrets.env" \
  || { echo "secrets.env is malformed." >&2; exit 1; }
echo "Password file is valid."

echo "==> [4/5] Writing compose.yaml and deploy.sh"
cat > "${APP_DIR}/compose.yaml" <<'COMPOSE_EOF'
name: theeverthread-dev

x-logging: &logging
  driver: json-file
  options:
    max-size: "10m"
    max-file: "3"

services:
  postgres:
    image: postgres:17.6-alpine
    restart: unless-stopped
    environment:
      POSTGRES_DB: theeverthread
      POSTGRES_USER: theeverthread
      POSTGRES_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD is required}
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U theeverthread -d theeverthread"]
      interval: 5s
      timeout: 3s
      retries: 20
    logging: *logging

  backend:
    image: ${BACKEND_IMAGE:?BACKEND_IMAGE is required}
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/theeverthread
      DB_USER: theeverthread
      DB_PASSWORD: ${DB_PASSWORD:?DB_PASSWORD is required}
      SERVER_ADDRESS: 0.0.0.0
      SERVER_PORT: "8080"
    mem_limit: 1536m
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://127.0.0.1:8080/actuator/health/readiness"]
      interval: 10s
      timeout: 5s
      start_period: 90s
      retries: 18
    logging: *logging

  frontend:
    image: ${FRONTEND_IMAGE:?FRONTEND_IMAGE is required}
    restart: unless-stopped
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "80:80"
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "/dev/null", "http://127.0.0.1/"]
      interval: 10s
      timeout: 5s
      retries: 6
    logging: *logging

volumes:
  postgres-data:
COMPOSE_EOF

cat > "${APP_DIR}/deploy.sh" <<'DEPLOY_EOF'
#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
cd /opt/theeverthread

# Serialize manual and automated deployments on this server.
exec 9>/opt/theeverthread/deploy.lock
flock -n 9 || { echo 'Another deployment is running'; exit 1; }

[[ $# == 2 ]] || { echo 'Usage: deploy.sh BACKEND_DIGEST FRONTEND_DIGEST'; exit 2; }
for image in "$@"; do
  [[ "$image" =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]] || {
    echo 'Expected a GHCR image reference with a sha256 digest'
    exit 2
  }
done

printf 'BACKEND_IMAGE=%s\nFRONTEND_IMAGE=%s\n' "$1" "$2" > candidate.env

compose() {
  docker compose --env-file /opt/theeverthread/secrets.env --env-file "$1" \
    -f /opt/theeverthread/compose.yaml "${@:2}"
}

# Pull before changing the running application. Database image is managed separately.
compose candidate.env pull backend frontend
if compose candidate.env up -d --wait --wait-timeout 300 && \
   curl --fail --silent --show-error --retry 6 --retry-delay 5 --retry-all-errors \
     http://127.0.0.1/api/status; then
  if [[ -f current.env ]]; then cp current.env previous.env; fi
  mv candidate.env current.env
  echo 'Deployment succeeded; current.env records the deployed image digests.'
else
  echo 'Deployment failed. Inspect docker compose logs and current.env.' >&2
  echo 'No automatic database or image rollback was attempted.' >&2
  # Return failure to SSM and therefore GitHub Actions.
  exit 1
fi
DEPLOY_EOF

chown root:root "${APP_DIR}/compose.yaml" "${APP_DIR}/deploy.sh"
chmod 600 "${APP_DIR}/compose.yaml"
chmod 700 "${APP_DIR}/deploy.sh"

echo "==> [5/5] Validating"
bash -n "${APP_DIR}/deploy.sh" && echo "Deployment script syntax is valid."
env BACKEND_IMAGE=backend:check FRONTEND_IMAGE=frontend:check \
  docker compose --env-file "${APP_DIR}/secrets.env" \
  -f "${APP_DIR}/compose.yaml" config --quiet \
  && echo "Compose configuration is valid."

echo
ls -l "${APP_DIR}"
echo
echo "Done. Containers are not running yet: they start when the CI pipeline"
echo "calls ${APP_DIR}/deploy.sh with the published image digests."