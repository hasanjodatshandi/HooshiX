#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/common.sh"
load_env "$ROOT/infrastructure/observability/pins.env"
tmp=$(mktemp -d)
chmod 0755 "$tmp"
trap 'rm -rf "$tmp"' EXIT
python3 - "$ROOT" "$tmp" <<'PY'
import sys,yaml
from pathlib import Path
root=Path(sys.argv[1]); out=Path(sys.argv[2])
def cm(src,name):
    docs=list(yaml.safe_load_all((root/src).read_text(encoding='utf-8')))
    return next(x for x in docs if x and x.get('kind')=='ConfigMap' and x['metadata']['name']==name)['data']
(out/'otel.yaml').write_text(cm(Path('infrastructure/observability/collector.yaml'),'otel-collector-config')['config.yaml'])
(out/'loki.yaml').write_text(cm(Path('infrastructure/observability/backends.yaml'),'loki-config')['config.yaml'])
(out/'tempo.yaml').write_text(cm(Path('infrastructure/observability/backends.yaml'),'tempo-config')['tempo.yaml'])
prom=cm(Path('infrastructure/observability/prometheus.yaml'),'prometheus-config')
(out/'prometheus.yml').write_text(prom['prometheus.yml'].replace('/etc/prometheus/rules.yml','/config/rules.yml'))
(out/'rules.yml').write_text(prom['rules.yml'])
for p in out.iterdir(): p.chmod(0o444)
PY
docker run --rm -v "$tmp/otel.yaml:/etc/otel/config.yaml:ro" "otel/opentelemetry-collector-contrib:${OTEL_COLLECTOR_VERSION}@${OTEL_COLLECTOR_INDEX_DIGEST}" validate --config=file:/etc/otel/config.yaml >/dev/null
docker run --rm -v "$tmp/loki.yaml:/etc/loki/config.yaml:ro" "grafana/loki:${LOKI_VERSION}@${LOKI_INDEX_DIGEST}" -config.file=/etc/loki/config.yaml -verify-config >/dev/null
docker run --rm -v "$tmp/tempo.yaml:/etc/tempo/tempo.yaml:ro" "grafana/tempo:${TEMPO_VERSION}@${TEMPO_INDEX_DIGEST}" -config.file=/etc/tempo/tempo.yaml -config.verify=true -target=all >/dev/null
docker run --rm --entrypoint /bin/promtool -v "$tmp/prometheus.yml:/config/prometheus.yml:ro" -v "$tmp/rules.yml:/config/rules.yml:ro" "prom/prometheus:v${PROMETHEUS_VERSION}@${PROMETHEUS_INDEX_DIGEST}" check config /config/prometheus.yml >/dev/null
echo "Observability exact-image configuration preflight PASSED"
