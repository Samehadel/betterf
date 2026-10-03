#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
cd /opt/betterf

# Serialize manual and automated deployments on this server.
exec 9>/opt/betterf/deploy.lock
flock -n 9 || { echo 'Another deployment is running'; exit 1; }

[[ $# == 2 ]] || { echo 'Usage: deploy.sh BACKEND_DIGEST FRONTEND_DIGEST'; exit 2; }
for image in "$@"; do
  [[ "$image" =~ ^ghcr\.io/[a-z0-9._/-]+@sha256:[a-f0-9]{64}$ ]] || {
    echo 'Expected a GHCR image reference with a sha256 digest'; exit 2;
  }
done

printf 'BACKEND_IMAGE=%s\nFRONTEND_IMAGE=%s\n' "$1" "$2" > candidate.env
compose() {
  docker compose --env-file /opt/betterf/secrets.env --env-file "$1" \
    -f /opt/betterf/compose.yaml "${@:2}"
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
