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

cd "$(dirname "$0")"

ENV_FILE=".env"
JAR="target/philter.jar"

# The encryption key must be the SAME on every run. It wraps the per-record data keys, so a key
# that changes leaves existing ledger entries, contexts, and API keys undecryptable. Generating it
# once into .env, which docker compose reads automatically, keeps it stable; `export
# PHILTER_ENCRYPTION_KEY=$(openssl rand -base64 32)` produces a new one in every shell.
if [ ! -f "${ENV_FILE}" ]; then

    if ! command -v openssl > /dev/null 2>&1; then
        echo "openssl is needed to generate PHILTER_ENCRYPTION_KEY but was not found." >&2
        echo "Install it, or write the key yourself: ${ENV_FILE} needs a line" >&2
        echo "  PHILTER_ENCRYPTION_KEY=<base64-encoded 32 bytes>" >&2
        exit 1
    fi

    KEY="$(openssl rand -base64 32)"

    # Written before the file is populated so the key is never briefly world-readable.
    touch "${ENV_FILE}"
    chmod 600 "${ENV_FILE}"
    printf 'PHILTER_ENCRYPTION_KEY=%s\n' "${KEY}" > "${ENV_FILE}"

    echo "Generated ${ENV_FILE} with a new PHILTER_ENCRYPTION_KEY."
    echo "Back it up and keep it out of version control: data encrypted with this key cannot be"
    echo "recovered without it, and Philter will not start if it changes."

elif ! grep -q '^PHILTER_ENCRYPTION_KEY=.' "${ENV_FILE}"; then

    echo "${ENV_FILE} exists but does not set PHILTER_ENCRYPTION_KEY." >&2
    echo "Add one, or delete ${ENV_FILE} and re-run this script to generate it:" >&2
    echo "  echo \"PHILTER_ENCRYPTION_KEY=\$(openssl rand -base64 32)\" >> ${ENV_FILE}" >&2
    exit 1

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
