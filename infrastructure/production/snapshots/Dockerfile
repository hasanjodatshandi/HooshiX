FROM docker.io/library/python@sha256:eb2c62658b8d51bff0acc408504b76521d02030de56a8a3c1e830083e89b89d2

USER root
RUN apk add --no-cache --upgrade \
      ca-certificates-bundle=20261020-r0 \
      curl=8.23.0-r0 \
      gpg=2.4.9-r1 \
      gpg-agent=2.4.9-r1 \
      gnupg-gpgconf=2.4.9-r1 \
      libcrypto3=3.5.9-r0 \
      libssl3=3.5.9-r0 \
    && addgroup -S -g 10001 snapshot \
    && adduser -S -D -H -u 10001 -G snapshot snapshot \
    && mkdir -p /app \
    && chown 10001:10001 /app

COPY --chown=10001:10001 openbao_snapshot_agent.py openbao_snapshot_crypto.py \
     openbao_snapshot_errors.py parspack_snapshot_transport.py /app/

LABEL org.opencontainers.image.version="1" \
      org.opencontainers.image.source="https://github.com/hasanjodatshandi/HooshiX" \
      io.hooshix.provenance.kind="hooshix-openbao-hourly-encrypted-snapshot"

ARG REPOSITORY_REVISION
ARG RECIPE_SHA256
LABEL org.opencontainers.image.revision="${REPOSITORY_REVISION}" \
      io.hooshix.recipe.sha256="${RECIPE_SHA256}"

WORKDIR /app
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/python3", "-I", "/app/openbao_snapshot_agent.py"]
