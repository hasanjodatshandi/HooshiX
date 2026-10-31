"""Sanitized failures shared by the owner and in-cluster snapshot paths."""


class SnapshotFailed(ValueError):
    """Only fixed failure codes are safe to report."""


def require(condition, code):
    if not condition:
        raise SnapshotFailed(code)
