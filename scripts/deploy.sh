#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
ENV_FILE=${TEAMDOCS_ENV_FILE:-$REPO_DIR/../config/.env.prod}
COMPOSE_FILE="$REPO_DIR/docker-compose.prod.yml"

if [ ! -f "$ENV_FILE" ]; then
  echo "Missing production environment file: $ENV_FILE" >&2
  exit 1
fi

cd "$REPO_DIR"
git pull --ff-only

IMAGE_TAG=$(git rev-parse --short=12 HEAD)
export IMAGE_TAG

docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" config --quiet
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" build --pull backend frontend
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --remove-orphans --wait --wait-timeout 180
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps
