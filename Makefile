PYTHON ?= python3

.PHONY: baseline-test baseline-verify script-static-verify platform-test performance-test production-test production-verify context-test context-verify context-bootstrap context-matrix-check context-post-merge-verify local-runtime-test local-runtime-up local-runtime-status local-runtime-smoke-erasure local-runtime-logs local-runtime-down local-runtime-reset kind-inotify-configure kind-inotify-verify local-cluster-up local-cluster-verify local-cluster-delete local-istio-ambient-install verify-local-istio-ambient local-istio-ambient-delete local-kyverno-install verify-local-kyverno local-traefik-edge-install verify-local-traefik-edge local-traefik-edge-delete local-observability-install verify-local-observability staging-data-install staging-build staging-deploy staging-verify production-fidelity-up production-fidelity-verify production-fidelity-down

baseline-test:
	$(PYTHON) -m unittest discover -s scripts/baseline/tests -p 'test_*.py'

script-static-verify:
	scripts/ci/quality/verify_repository_sources.sh

platform-test:
	$(PYTHON) -m unittest discover -s scripts/platform/tests -p 'test_*.py'

performance-test:
	$(PYTHON) -m unittest discover -s scripts/performance/tests -p 'test_*.py'

production-test:
	$(PYTHON) -m unittest discover -s scripts/production/tests -p 'test_*.py'

production-verify: production-test
	$(PYTHON) scripts/production/verify.py

context-test:
	$(PYTHON) -m unittest discover -s scripts/context/tests -p 'test_*.py'

context-post-merge-verify:
	$(PYTHON) scripts/context/post_merge_checkpoint.py verify

context-verify:
	$(PYTHON) scripts/context/context_engine.py verify
	$(PYTHON) scripts/context/post_merge_checkpoint.py verify

context-bootstrap:
	$(PYTHON) scripts/context/context_engine.py bootstrap

context-matrix-check:
	@tmp_file=$$(mktemp); \
	$(PYTHON) scripts/context/context_engine.py matrix > $$tmp_file; \
	cmp -s $$tmp_file docs/architecture/TASK-REVIEW-MATRIX.md || { \
		echo 'TASK-REVIEW-MATRIX.md differs from context/routes.json' >&2; \
		rm -f $$tmp_file; \
		exit 1; \
	}; \
	rm -f $$tmp_file

baseline-verify: baseline-test platform-test performance-test production-verify context-test context-verify context-matrix-check local-runtime-test
	$(PYTHON) scripts/baseline/verify_repository.py

local-runtime-test:
	$(PYTHON) -m unittest discover -s scripts/local/tests -p 'test_*.py'

local-runtime-up:
	$(PYTHON) scripts/local/runtime.py up

local-runtime-status:
	$(PYTHON) scripts/local/runtime.py status

local-runtime-smoke-erasure:
	$(PYTHON) scripts/local/runtime.py smoke-erasure

local-runtime-logs:
	$(PYTHON) scripts/local/runtime.py logs

local-runtime-down:
	$(PYTHON) scripts/local/runtime.py down

local-runtime-reset:
	$(PYTHON) scripts/local/runtime.py down --remove-data
kind-inotify-configure:
	scripts/platform/kind_inotify_configure.sh

kind-inotify-verify:
	scripts/platform/kind_inotify_verify.sh

local-cluster-up:
	scripts/platform/kind_create.sh
	scripts/platform/registry_up.sh

local-cluster-verify:
	scripts/platform/kind_verify.sh

local-cluster-delete:
	scripts/platform/kind_delete.sh

local-registry-down:
	scripts/platform/registry_down.sh

local-istio-ambient-install:
	scripts/platform/istio_install.sh

verify-local-istio-ambient:
	scripts/platform/istio_verify.sh

local-istio-ambient-delete:
	scripts/platform/istio_delete.sh

local-kyverno-install:
	scripts/platform/kyverno_install.sh

verify-local-kyverno:
	scripts/platform/kyverno_verify.sh

local-traefik-edge-install:
	scripts/platform/waf_build.sh
	scripts/platform/edge_install.sh

verify-local-traefik-edge:
	scripts/platform/edge_verify.sh

local-traefik-edge-delete:
	scripts/platform/edge_delete.sh

local-observability-install:
	scripts/platform/observability_install.sh

verify-local-observability:
	scripts/platform/observability_verify.sh

staging-data-install:
	scripts/platform/staging_dataset_install.sh
	scripts/platform/staging_data_install.sh

staging-build:
	scripts/platform/staging_build_all.sh

staging-deploy:
	scripts/platform/staging_deploy_all.sh

staging-verify:
	scripts/platform/staging_verify.sh

production-fidelity-up:
	scripts/platform/platform_up.sh

production-fidelity-verify:
	scripts/platform/platform_verify.sh

production-fidelity-down:
	scripts/platform/platform_down.sh
