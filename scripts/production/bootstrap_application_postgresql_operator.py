"""Existing owner-local custody transport; projection-only bundle and supervisor."""
import bootstrap_postgresql_secrets_operator as operator
from build_application_postgresql_bundle import SOURCES

if __name__ == '__main__':
    raise SystemExit(operator.main(sources=SOURCES, supervisor='bootstrap_application_postgresql_delivery',
                                  application_projection=True))
