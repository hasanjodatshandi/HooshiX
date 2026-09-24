ARG CADDY_BUILDER_IMAGE=docker.io/library/caddy@sha256:c085abfb0fc68e5cbde7dcbf2cbf5ca416a75a6a3343372586c1851e960885cc
ARG CADDY_RUNTIME_IMAGE=docker.io/library/caddy@sha256:df7f1c2fb114453b951de51a98efc010db1655a92c2e86be6706714e2417a78d
FROM ${CADDY_BUILDER_IMAGE} AS builder
ARG SOURCE_DATE_EPOCH
ENV GOPROXY=https://proxy.golang.org,direct GOSUMDB=sum.golang.org
COPY patches/ /tmp/hooshix-waf-patches/
RUN --mount=type=cache,target=/go/pkg/mod \
    mkdir /tmp/waf-upstream-check && cd /tmp/waf-upstream-check && \
    go mod init hooshix.local/waf-upstream-check && \
    go mod edit -require=github.com/corazawaf/coraza-caddy/v2@v2.5.0 && \
    go mod download github.com/corazawaf/coraza-caddy/v2@v2.5.0 && go mod verify && \
    test "$(cat /go/pkg/mod/cache/download/github.com/corazawaf/coraza-caddy/v2/@v/v2.5.0.ziphash)" = 'h1:zdK/o14duUII/UCeJo5yozN1MhinASGULrJgQT+GIG4=' && \
    cp -a /go/pkg/mod/github.com/corazawaf/coraza-caddy/v2@v2.5.0 /tmp/coraza-caddy && \
    chmod u+w /tmp/coraza-caddy/coraza.go && \
    cd /tmp/coraza-caddy && \
    git apply --check --unidiff-zero /tmp/hooshix-waf-patches/safe-rule-logging.patch && \
    git apply --unidiff-zero /tmp/hooshix-waf-patches/safe-rule-logging.patch && \
    cp /tmp/hooshix-waf-patches/hooshix_logging_test.go /tmp/coraza-caddy/
COPY vendor/4.25.1/coreruleset-4.25.1.tar.gz /tmp/crs.tar.gz
RUN mkdir -p /tmp/crs && tar -xzf /tmp/crs.tar.gz --strip-components=1 -C /tmp/crs && \
    grep -q 'OWASP CRS ver.4.25.1' /tmp/crs/crs-setup.conf.example && \
    find /tmp/crs -exec touch -h -d "@${SOURCE_DATE_EPOCH}" {} +
COPY opaque-cookie-exclusions.conf /tmp/opaque-cookie-exclusions.conf
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    cd /tmp/coraza-caddy && go test -mod=readonly -run '^TestHooshix' .
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    xcaddy build v2.11.4 \
      --with github.com/corazawaf/coraza-caddy/v2@v2.5.0=/tmp/coraza-caddy && \
    touch -h -d "@${SOURCE_DATE_EPOCH}" /usr/bin/caddy
FROM ${CADDY_RUNTIME_IMAGE}
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
RUN setcap -r /usr/bin/caddy || true
COPY --from=builder /tmp/crs /etc/coraza/crs
COPY Caddyfile /etc/caddy/Caddyfile
COPY opaque-cookie-exclusions.conf /etc/coraza/opaque-cookie-exclusions.conf
ENV XDG_CONFIG_HOME=/config XDG_DATA_HOME=/data
USER 10001:10001
EXPOSE 8080 9090
ENTRYPOINT ["caddy","run","--config","/etc/caddy/Caddyfile","--adapter","caddyfile"]
