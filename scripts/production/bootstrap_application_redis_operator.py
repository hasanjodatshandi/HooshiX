"""Existing owner-local custody transport for scoped Redis projections."""
import bootstrap_postgresql_secrets_operator as operator
from build_application_redis_bundle import SOURCES

if __name__ == '__main__':
    raise SystemExit(operator.main(sources=SOURCES, supervisor='bootstrap_application_redis_delivery',
                                  application_redis=True))
