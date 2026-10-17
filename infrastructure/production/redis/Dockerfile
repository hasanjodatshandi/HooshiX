FROM docker.io/library/redis@sha256:3790f265260957949f41e767efd9b66d5e3f277913b63a56c0183d55a47e7347 AS upstream
FROM docker.io/library/alpine@sha256:d56c381f961d307a21b3ca004cf1e3910f106644aefb1f43e654c8a56c4fd395
RUN apk add --no-cache libcrypto3=3.5.9-r0 libssl3=3.5.9-r0 zlib=1.3.2-r1 libgcc=15.2.0-r5 libstdc++=15.2.0-r5 \
    && addgroup -S -g 10001 redis && adduser -S -D -H -u 10001 -G redis redis \
    && mkdir -p /data /usr/share/redis-source /usr/share/licenses/redis && chown 10001:10001 /data
COPY --from=upstream /usr/local/bin/redis-server /usr/local/bin/redis-cli /usr/local/bin/
COPY source.tar.gz /usr/share/redis-source/source.tar.gz
RUN echo '94100f4fb2067b28becdb957d3f56253e322cb3ed800238dfb29e44086bce066  /usr/share/redis-source/source.tar.gz' | sha256sum -c - \
    && tar -xOf /usr/share/redis-source/source.tar.gz redis-382bda1c5a98bc0c0376930cf30ffd92eb3c5c18/LICENSE.txt > /usr/share/licenses/redis/LICENSE.txt
LABEL org.opencontainers.image.version="8.2.8" \
      org.opencontainers.image.source="https://github.com/hasanjodatshandi/HooshiX" \
      io.hooshix.provenance.kind="patched-upstream-runtime-build" \
      io.hooshix.redis.source.revision="382bda1c5a98bc0c0376930cf30ffd92eb3c5c18"
WORKDIR /data
USER 10001:10001
ENTRYPOINT ["/usr/local/bin/redis-server"]
CMD ["/run/secrets/security-redis/redis.conf"]
