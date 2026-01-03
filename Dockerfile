FROM ubuntu:24.04

ARG PHILTER_VERSION

RUN apt-get update && apt-get -y install openjdk-21-jre

RUN mkdir -p /opt/philter/ssl && mkdir -p /opt/philter/policies

COPY ./README.md /opt/philter/
COPY ./LICENSE.txt /opt/philter/
COPY ./distribution/policies /opt/philter/policies/
COPY ./distribution/philter.properties /opt/philter/

ADD ./target/philter.jar /opt/philter/

RUN chmod +x /opt/philter/philter.jar

EXPOSE 8080

WORKDIR /opt/philter
CMD ["java", "-jar", "/opt/philter/philter.jar"]
