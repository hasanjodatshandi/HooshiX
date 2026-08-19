PYTHON ?= python3

.PHONY: baseline-test baseline-verify context-test context-verify context-bootstrap context-matrix-check context-post-merge-verify local-runtime-test local-runtime-up local-runtime-status local-runtime-logs local-runtime-down local-runtime-reset

baseline-test:
	$(PYTHON) -m unittest discover -s scripts/baseline/tests -p 'test_*.py'

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

baseline-verify: baseline-test context-test context-verify context-matrix-check local-runtime-test
	$(PYTHON) scripts/baseline/verify_repository.py

local-runtime-test:
	$(PYTHON) -m unittest discover -s scripts/local/tests -p 'test_*.py'

local-runtime-up:
	$(PYTHON) scripts/local/runtime.py up

local-runtime-status:
	$(PYTHON) scripts/local/runtime.py status

local-runtime-logs:
	$(PYTHON) scripts/local/runtime.py logs

local-runtime-down:
	$(PYTHON) scripts/local/runtime.py down

local-runtime-reset:
	$(PYTHON) scripts/local/runtime.py down --remove-data
