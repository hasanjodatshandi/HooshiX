# Context Checkpoints

This directory contains append-only, commit-bound work and post-merge checkpoints created by the Git-native Agent Context Engine.

Checkpoints are **historical evidence, not architecture authority**. Current Git authority always wins. A consumer must compare the checkpoint `subject_commit` with current `HEAD` and inspect intervening changes before using the checkpoint for continuity.

A normal work checkpoint records a coherent implementation/review state before merge. A `post-merge` checkpoint links to that earlier record with `source_checkpoint` and binds the final evidence to the exact merged `main` commit. The earlier record remains unchanged even when its pre-merge `next_actions` are later completed.

Create checkpoints only through the repository commands documented in `docs/engineering/agent-context-engine.md`. Do not store passwords, tokens, API keys, private keys, credentials, raw production secrets, or copied source/document bodies here.

A checkpoint-only follow-up PR transports post-merge historical evidence and does not recursively require another post-merge checkpoint. If substantive implementation or governance work is added to that PR, the normal checkpoint lifecycle applies.

The canonical record contract is `../checkpoint.schema.json`.
