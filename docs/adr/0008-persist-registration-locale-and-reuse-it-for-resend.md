# ADR-0008: Persist Registration Locale and Reuse It for Resend

## Status

Accepted — current effective decision

## Date

2026-08-10; normalized to current-only documentation on 2026-08-13

## Decision

Notification requires an explicit canonical locale for semantic template selection and prohibits silent language fallback.

`RegisterLocalRequest` uses field `5`, `RegistrationLocale locale`. The enum contains `REGISTRATION_LOCALE_FA` and `REGISTRATION_LOCALE_EN` plus the required zero `UNSPECIFIED` value. Identity treats `UNSPECIFIED` and unrecognized values as `INVALID_ARGUMENT`; the effective canonical values are `fa` and `en`.

Identity persists the locale immutably with each registration verification challenge. `ResendRegistrationVerificationRequest` does not accept a locale. A replacement challenge and its delivery intent reuse the locale persisted on the previous challenge, so a caller cannot change language during resend.

The registration-challenge locale column is `NOT NULL`, and Domain, Application, and Persistence layers have no locale-less challenge state. The application never guesses or defaults to `fa` or `en`.

The initial migration assumes no pre-existing locale-less challenge rows. If an unexpected row exists, migration must stop and require an explicit reviewed data-migration plan rather than inventing a locale.

## Security and verification requirements

- gRPC contract tests pin field number `5` and accepted enum values;
- adapter tests reject missing locale before invoking the application use case;
- application tests prove initial delivery receives the selected locale;
- resend tests prove the replacement and sender reuse the persisted locale;
- persistence tests prove the canonical value survives a database round trip;
- migration tests prove unexpected locale-less rows block rather than receiving a guessed locale.

## Rollback considerations

The Protobuf field and database column are additive. Older compatible readers may ignore field `5`; current writers reject new registrations that omit it. Rollback leaves the column intact and preserves persisted locale semantics. Executed Flyway migrations are never reversed or edited.
