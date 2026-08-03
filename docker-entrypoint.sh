#!/bin/sh
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

set -e

KEYSTORE="${SSL_KEYSTORE:-/opt/philter/ssl/philter.p12}"
KEYSTORE_PASSWORD="${SSL_KEYSTORE_PASSWORD:-philter}"
KEY_ALIAS="${SSL_KEY_ALIAS:-philter}"
CERTIFICATE_HOSTNAME="${SSL_CERTIFICATE_HOSTNAME:-localhost}"

# Generate a certificate on first start when TLS is on and the deployment has not supplied one.
# Generating here rather than in the image build keeps the private key out of the published image,
# so every deployment gets its own instead of one shared by everyone who pulls the image.
if [ "${SSL_ENABLED:-true}" = "true" ] && [ ! -f "${KEYSTORE}" ]; then

    echo "Generating a self-signed TLS certificate for ${CERTIFICATE_HOSTNAME} at ${KEYSTORE}."
    echo "It is not signed by a certificate authority, so clients must skip verification (curl -k)."
    echo "For production, mount your own keystore and set SSL_KEYSTORE, or terminate TLS upstream."

    mkdir -p "$(dirname "${KEYSTORE}")"

    # `philter` is the service name in docker-compose.yml, so other containers reach it by that name.
    SAN="DNS:${CERTIFICATE_HOSTNAME},DNS:philter,IP:127.0.0.1"
    if [ "${CERTIFICATE_HOSTNAME}" != "localhost" ]; then
        SAN="${SAN},DNS:localhost"
    fi

    keytool -genkeypair \
        -alias "${KEY_ALIAS}" \
        -keyalg RSA \
        -keysize 2048 \
        -sigalg SHA256withRSA \
        -validity 3650 \
        -storetype PKCS12 \
        -keystore "${KEYSTORE}" \
        -storepass "${KEYSTORE_PASSWORD}" \
        -dname "CN=${CERTIFICATE_HOSTNAME}, O=Philterd, C=US" \
        -ext "SAN=${SAN}" \
        -ext "BasicConstraints=ca:false"

fi

exec "$@"
