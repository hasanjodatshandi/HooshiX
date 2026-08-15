PYTHON ?= python3

.PHONY: baseline-test baseline-verify

baseline-test:
	$(PYTHON) -m unittest discover -s scripts/baseline/tests -p 'test_*.py'

baseline-verify: baseline-test
	$(PYTHON) scripts/baseline/verify_repository.py
