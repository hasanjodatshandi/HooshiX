PYTHON ?= python3

.PHONY: baseline-test baseline-verify context-test context-verify context-bootstrap context-matrix-check

baseline-test:
	$(PYTHON) -m unittest discover -s scripts/baseline/tests -p 'test_*.py'

context-test:
	$(PYTHON) -m unittest discover -s scripts/context/tests -p 'test_*.py'

context-verify:
	$(PYTHON) scripts/context/context_engine.py verify

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

baseline-verify: baseline-test context-test context-verify context-matrix-check
	$(PYTHON) scripts/baseline/verify_repository.py
