#!/usr/bin/env bash
set -euo pipefail
container_id=$(docker run -d --network host -e DATABASE_URL="$TEST_DATABASE_URL" -e PORT=18080 metricme-test)
trap 'docker rm -f "$container_id" >/dev/null' EXIT
ready=0
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:18080/health/ready > /tmp/metricme-ready.json; then ready=1; break; fi
  sleep 1
done
if [ "$ready" != 1 ]; then docker logs "$container_id"; exit 1; fi
python3 -c 'import json; d=json.load(open("/tmp/metricme-ready.json")); assert d=={"status":"ready","aiConfigured":False,"authConfigured":False},d'
status=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' http://127.0.0.1:18080/v1/food-estimates)
test "$status" = 401
test "$(docker inspect --format '{{.Config.User}}' "$container_id")" = metricme
echo 'Container ready with PostgreSQL; protected routes reject unauthenticated requests; runtime user is non-root.'
