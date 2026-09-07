#!/usr/bin/env bash
#
#     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#          http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Wraps `docker compose` with the setup Philter needs, so the quick start works without exporting
# anything by hand.
#
#   ./compose.sh              same as: docker compose build
#   ./compose.sh up -d        any other docker compose command, arguments passed through
#

set -euo pipefail
umask 077

cd "$(dirname "$0")"

ENV_FILE=".env"
JAR="target/philter.jar"

# Generated once into .env, which docker compose reads automatically. The encryption key must not
# change: it wraps the per-record data keys, so a new one orphans all existing encrypted data.
if [ ! -f "${ENV_FILE}" ]; then
    # Created empty and locked down first, so no secret is ever briefly world-readable.
    touch "${ENV_FILE}"
    chmod 600 "${ENV_FILE}"
fi
chmod 600 "${ENV_FILE}"

if ! grep -q '^PHILTER_ENCRYPTION_KEY=.' "${ENV_FILE}" \
        || ! grep -q '^PHILTER_BOOTSTRAP_API_KEY=.' "${ENV_FILE}" \
        || ! grep -q '^PHILTER_BOOTSTRAP_ADMIN_PASSWORD=.' "${ENV_FILE}" \
        || ! grep -q '^MONGODB_PASSWORD=.' "${ENV_FILE}"; then
    if ! command -v openssl > /dev/null 2>&1; then
        echo "openssl is needed to generate the keys in ${ENV_FILE} but was not found." >&2
        echo "Install it, or add these lines yourself:" >&2
        echo "  PHILTER_ENCRYPTION_KEY=<base64-encoded 32 bytes>" >&2
        echo "  PHILTER_BOOTSTRAP_API_KEY=sk_<32 alphanumeric characters>" >&2
        echo "  PHILTER_BOOTSTRAP_ADMIN_PASSWORD=<a private password, at least 16 characters>" >&2
        echo "  MONGODB_PASSWORD=<a password, url-safe>" >&2
        exit 1
    fi
fi

if ! grep -q '^PHILTER_ENCRYPTION_KEY=.' "${ENV_FILE}"; then
    printf 'PHILTER_ENCRYPTION_KEY=%s\n' "$(openssl rand -base64 32)" >> "${ENV_FILE}"
    echo "Generated PHILTER_ENCRYPTION_KEY in ${ENV_FILE}. Back it up: data encrypted with it"
    echo "cannot be recovered without it, and Philter will not start if it changes."
fi

# openssl rand -hex 16 gives the 32 alphanumeric characters Philter requires after "sk_".
if ! grep -q '^PHILTER_BOOTSTRAP_API_KEY=.' "${ENV_FILE}"; then
    printf 'PHILTER_BOOTSTRAP_API_KEY=sk_%s\n' "$(openssl rand -hex 16)" >> "${ENV_FILE}"
    echo "Generated PHILTER_BOOTSTRAP_API_KEY in ${ENV_FILE}."
fi

# A private first-login password, delivered only through the owner-readable .env file.
if ! grep -q '^PHILTER_BOOTSTRAP_ADMIN_PASSWORD=.' "${ENV_FILE}"; then
    printf 'PHILTER_BOOTSTRAP_ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 24)" >> "${ENV_FILE}"
    echo "Generated PHILTER_BOOTSTRAP_ADMIN_PASSWORD in ${ENV_FILE}. Use it for the first admin login."
fi

# Hex so it needs no URL-encoding: it goes into MONGODB_CONNECTION_STRING as-is. Set once, because
# MongoDB only reads it when it initialises an empty data directory.
if ! grep -q '^MONGODB_PASSWORD=.' "${ENV_FILE}"; then
    printf 'MONGODB_PASSWORD=%s\n' "$(openssl rand -hex 24)" >> "${ENV_FILE}"
    echo "Generated MONGODB_PASSWORD in ${ENV_FILE}."
fi

# The image copies the jar rather than building it, so Maven has to have run first. Checking here
# turns a confusing "file not found" from the Docker build into an actionable message.
if [ ! -f "${JAR}" ]; then
    echo "${JAR} not found. Build it first:" >&2
    echo "  mvn clean install" >&2
    exit 1
fi

if [ $# -eq 0 ]; then
    set -- build
fi

exec docker compose "$@"
