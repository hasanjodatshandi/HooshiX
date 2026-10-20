FROM apache/kafka@sha256:daa39dc85acd37b148fad74d6b22b60fa5f7fa05048a5c8aa3b654140c63dd58

USER root
COPY overlay-jars/ /tmp/kafka-overlay/
RUN set -eux; \
    apk add --no-cache --upgrade \
      libcrypto3=3.5.9-r0 libexpat=2.9.0-r0 libssl3=3.5.9-r0 \
      openssl=3.5.9-r0 zlib=1.3.2-r1; \
    rm -f /opt/kafka/libs/jackson-core-2.21.6.jar \
      /opt/kafka/libs/jackson-databind-2.21.6.jar \
      /opt/kafka/libs/jackson-dataformat-csv-2.21.6.jar \
      /opt/kafka/libs/jackson-dataformat-yaml-2.21.6.jar \
      /opt/kafka/libs/jackson-datatype-jdk8-2.21.6.jar \
      /opt/kafka/libs/jackson-jakarta-rs-base-2.21.6.jar \
      /opt/kafka/libs/jackson-jakarta-rs-json-provider-2.21.6.jar \
      /opt/kafka/libs/jackson-module-blackbird-2.21.6.jar \
      /opt/kafka/libs/jackson-module-jakarta-xmlbind-annotations-2.21.6.jar \
      /opt/kafka/libs/lz4-java-1.11.2.jar; \
    cp /tmp/kafka-overlay/*.jar /opt/kafka/libs/; \
    rm -rf /tmp/kafka-overlay; \
    for pin in libcrypto3=3.5.9-r0 libexpat=2.9.0-r0 libssl3=3.5.9-r0 openssl=3.5.9-r0 zlib=1.3.2-r1; do \
      package="${pin%%=*}"; version="${pin#*=}"; \
      installed="$(apk query --from installed --fields version --format yaml "$package" | sed -n 's/^- version: //p')"; \
      test "$installed" = "$version"; \
    done; \
    ! find /opt/kafka/libs -maxdepth 1 -type f \( -name 'jackson-*-2.21.6.jar' -o -name 'lz4-java-1.11.2.jar' \) | grep -q .

LABEL org.opencontainers.image.version="4.2.2" \
      org.opencontainers.image.source="https://github.com/hasanjodatshandi/HooshiX" \
      io.hooshix.provenance.kind="upstream-runtime-security-overlay" \
      io.hooshix.kafka.upstream.image="apache/kafka@sha256:daa39dc85acd37b148fad74d6b22b60fa5f7fa05048a5c8aa3b654140c63dd58"

USER appuser
