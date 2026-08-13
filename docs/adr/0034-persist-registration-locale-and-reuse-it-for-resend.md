# ADR-0034: Persist Registration Locale and Reuse It for Resend

## Status

Accepted

## Date

2026-08-10

## Context

Notification requires an explicit canonical locale for semantic template
selection and prohibits silent language fallback. Identity registration did not
previously carry or persist that input, so a resend could not prove which
locale belonged to the original registration context.

## Decision

`RegisterLocalRequest` adds field `5`, `RegistrationLocale locale`. The enum
contains `REGISTRATION_LOCALE_FA` and `REGISTRATION_LOCALE_EN` plus the required
zero `UNSPECIFIED` value. Identity treats `UNSPECIFIED` and unrecognized values
as `INVALID_ARGUMENT`; the effective canonical values are `fa` and `en`.

Identity persists the locale immutably with each new registration verification
challenge. `ResendRegistrationVerificationRequest` remains unchanged and does
not accept a locale. A replacement challenge and its delivery intent reuse the
locale persisted on the previous challenge, so a caller cannot change language
during resend.

The project has no existing registration data. The new challenge column is
therefore `NOT NULL` from its first migration and the Domain, Application, and
Persistence layers have no locale-less challenge state. The application never
guesses or defaults to `fa` or `en`.

## Security and Verification Requirements

- gRPC contract tests pin field number `5` and accepted enum values;
- adapter tests reject missing locale before invoking the application use case;
- application tests prove initial delivery receives the selected locale;
- resend tests prove the replacement and sender reuse the persisted locale;
- persistence tests prove the canonical value survives a database round trip.

## Consequences

- Template localization is explicit and stable across resend.
- Resend cannot be used to override presentation language.
- Locale-less challenges cannot be created or persisted.

## Rollback or Migration Considerations

The Protobuf field and database column are additive. An older application
ignores field `5`; a newer application rejects new registrations that omit it.
The migration intentionally requires an empty challenge table; an unexpected
existing row blocks migration rather than receiving an invented locale.
Rollback must leave the column intact. Executed Flyway migrations are never
reversed or edited.
