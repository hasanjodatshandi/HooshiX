# Context Checkpoints

This directory contains append-only, commit-bound work checkpoints created by the Git-native Agent Context Engine.

Checkpoints are **historical evidence, not architecture authority**. Current Git authority always wins. A consumer must compare the checkpoint `subject_commit` with current `HEAD` and inspect intervening changes before using the checkpoint for continuity.

Create a checkpoint only through the repository command documented in `docs/engineering/agent-context-engine.md`. Do not store passwords, tokens, API keys, private keys, credentials, raw production secrets, or copied source/document bodies here.

The canonical record contract is `../checkpoint.schema.json`.
