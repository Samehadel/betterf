#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env ]]; then
  echo 'Create .env from .env.example and set DB_PASSWORD first.' >&2
  exit 1
fi
set -a
source .env
set +a
: "${DB_PASSWORD:?Set DB_PASSWORD in .env}"
cd backend
exec ./gradlew bootRun
