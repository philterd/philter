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

# --- Runtime image ---
FROM ubuntu:24.04

ARG PHILTER_VERSION

RUN apt-get update && apt-get -y install openjdk-25-jre

RUN mkdir -p /opt/philter/ssl && mkdir -p /opt/philter/policies

COPY ./README.md /opt/philter/
COPY ./LICENSE.txt /opt/philter/

ADD ./target/philter.jar /opt/philter/

RUN chmod +x /opt/philter/philter.jar

# Built documentation, served by Philter at /public/docs/ (see WebConfig). The footer's
# "Documentation" link points to /public/docs/index.html.
COPY --from=docs /site/ /opt/philter/public/docs/

EXPOSE 8080

WORKDIR /opt/philter
CMD ["java", "-jar", "/opt/philter/philter.jar"]
