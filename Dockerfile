# --- Documentation build stage ---
# Builds the MkDocs documentation so it can be served by Philter at /public/docs/.
FROM python:3.12-slim AS docs

WORKDIR /docs

COPY docs/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# Only the MkDocs config and sources are needed (avoids copying the local venv/site).
COPY docs/mkdocs.yml ./mkdocs.yml
COPY docs/docs ./docs

RUN mkdocs build --site-dir /site

# --- Model stage ---
# The PhEye model the default policy names, ph-eye-pii-en-small, at a pinned Hugging Face revision. Each file is
# checked against its SHA-256, so a changed or corrupted download fails the build. The model is CC-BY-4.0, so its
# model card, which carries the attribution the license requires, is shipped beside it.
FROM ubuntu:24.04 AS model

ARG PHEYE_MODEL_REVISION=375cfd7bd6b4fa0ef618333048b9e94b92c35c94

RUN apt-get update && apt-get -y install --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /model
RUN for file in gliner_config.json tokenizer.json model.onnx; do \
        curl -fsSL -o "$file" "https://huggingface.co/philterd/ph-eye-pii-en-small/resolve/${PHEYE_MODEL_REVISION}/onnx/$file"; \
    done \
    && curl -fsSL -o README.md "https://huggingface.co/philterd/ph-eye-pii-en-small/resolve/${PHEYE_MODEL_REVISION}/README.md" \
    && printf '%s\n' \
        "5ec1db300425bad5846ae36ca732bb10a404a4d4eef37f620f1184fb2561918d  gliner_config.json" \
        "a3990e1379927bc3f9321b48a37f291bb29cd975fac294030ab989f9e32458ab  tokenizer.json" \
        "0ec8b8585ee8e60334efad4206e643b9575d6bf2f9b3e93604183f8aaaf3b40d  model.onnx" \
        "b2524b6216ab14aa6c8d2b8fcdd84706f89cf6b6c4a0ff20954d6d902c0b8594  README.md" \
        | sha256sum -c -

# --- Runtime image ---
FROM ubuntu:24.04

ARG PHILTER_VERSION

RUN apt-get update && apt-get -y upgrade \
    && apt-get -y install openjdk-25-jre \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/philter/ssl && mkdir -p /opt/philter/policies && mkdir -p /opt/philter/data && mkdir -p /opt/philter/models

COPY ./README.md /opt/philter/
COPY ./LICENSE.txt /opt/philter/

# The container serves HTTPS. The entrypoint generates a self-signed certificate into
# /opt/philter/ssl on first start unless a keystore is already there, so mounting your own over
# that path (or pointing SSL_KEYSTORE elsewhere) replaces it. Set SSL_ENABLED=false to serve plain
# HTTP instead, which is what you want when a load balancer or ingress terminates TLS.
ENV SSL_ENABLED=true
# SQLite storage, the default, keeps its file here; mount a volume at /opt/philter/data to keep it.
# Only the philter user can read the directory, since the database holds password hashes and the audit log.
ENV SQLITE_PATH=/opt/philter/data/philter.db

COPY ./docker-entrypoint.sh /opt/philter/
RUN chmod +x /opt/philter/docker-entrypoint.sh

ADD ./target/philter.jar /opt/philter/

RUN chmod +x /opt/philter/philter.jar

# Built documentation, served by Philter at /public/docs/ (see WebConfig).
COPY --from=docs /site/ /opt/philter/public/docs/

# The model the default policy names, in PHEYE_MODELS_DIRECTORY.
COPY --from=model /model/ /opt/philter/models/ph-eye-pii-en-small/

RUN groupadd --system --gid 10001 philter \
    && useradd --system --uid 10001 --gid philter --home-dir /opt/philter \
        --shell /usr/sbin/nologin philter \
    && chown -R philter:philter /opt/philter \
    && chmod 700 /opt/philter/data

USER philter

EXPOSE 8080

WORKDIR /opt/philter
ENTRYPOINT ["/opt/philter/docker-entrypoint.sh"]
# The JVM otherwise takes a quarter of the container's memory for its heap. PDF redaction renders pages in memory.
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/philter/philter.jar"]
