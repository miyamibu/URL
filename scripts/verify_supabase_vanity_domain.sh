#!/usr/bin/env bash
set -euo pipefail

PROJECT_REF="${1:-xocumgxbylmpoobfqows}"
DESIRED_SUBDOMAIN="${2:-linbam}"
TARGET_HOST="${DESIRED_SUBDOMAIN}.supabase.co"

export SUPABASE_TELEMETRY=false

echo "Project ref: ${PROJECT_REF}"
echo "Desired vanity domain: https://${TARGET_HOST}"
echo

echo "Checking current vanity subdomain status..."
if ! supabase vanity-subdomains get --project-ref "${PROJECT_REF}" --output json; then
  echo
  echo "Current status could not be read. If the error mentions Pro, Team, or Enterprise,"
  echo "upgrade the Supabase organization plan before continuing."
fi

echo
echo "Checking desired subdomain availability..."
if ! supabase vanity-subdomains check-availability \
  --project-ref "${PROJECT_REF}" \
  --desired-subdomain "${DESIRED_SUBDOMAIN}" \
  --output json; then
  echo
  echo "Availability could not be checked. If the error mentions Pro, Team, or Enterprise,"
  echo "upgrade the Supabase organization plan before continuing."
fi

echo
echo "Checking DNS/HTTPS reachability..."
if curl -fsS --max-time 15 "https://${TARGET_HOST}/auth/v1/health" >/dev/null; then
  echo "Reachable: https://${TARGET_HOST}"
else
  echo "Not reachable yet: https://${TARGET_HOST}"
fi

