# ADR-0053: Define Identity Password Policy v1

## Status

Accepted

## Context

The Identity service implements local credential storage, authentication, and compromised-password screening, but the concrete password composition policy required for password lifecycle operations was intentionally left undefined.

Password change and recovery flows must not introduce an implicit or inconsistent password rule inside application code. The policy must be stable before credential lifecycle APIs are implemented.

## Decision

Identity v1 password policy is:

- Password input MUST be NFC normalized before policy evaluation and credential processing.
- Password length is evaluated by Unicode code point count, not UTF-8 byte length.
- Minimum password length is 12 code points.
- Maximum accepted password length is 128 code points.
- Passwords MUST NOT require arbitrary composition rules such as mandatory uppercase, lowercase, digit, or symbol classes.
- Common/breached password screening through the Compromised Password service remains mandatory.
- Argon2id remains the credential storage authority.
- Password history/reuse prevention is not implemented in v1.
- Password validation failures must use stable non-sensitive error contracts.

## Consequences

Positive:

- Password lifecycle APIs have a deterministic security contract.
- Unicode handling is explicit and consistent.
- Users are not forced into weak composition patterns that encourage predictable passwords.

Negative:

- Future changes require a reviewed ADR update.
- Longer passwords require bounded input handling and tests.

## Enforcement

Password lifecycle implementations MUST reference this ADR and MUST NOT introduce independent password rules in service, BFF, or frontend layers.
