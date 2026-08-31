package com.sajtech.identity.configuration;

import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.authentication.usecase.*;
import com.sajtech.identity.application.externalidentity.port.out.*;
import com.sajtech.identity.application.externalidentity.usecase.ExternalIdentityUseCase;
import com.sajtech.identity.application.mfa.port.in.*;
import com.sajtech.identity.application.mfa.port.out.*;
import com.sajtech.identity.application.mfa.usecase.MfaUseCase;
import com.sajtech.identity.application.notification.port.out.*;
import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.password.port.out.PasswordRecoverySecretPort;
import com.sajtech.identity.application.password.port.out.PasswordRecoveryStore;
import com.sajtech.identity.application.password.usecase.*;
import com.sajtech.identity.application.profile.port.in.ProfileManagement;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.application.registration.usecase.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.infrastructure.observability.*;
import com.sajtech.identity.infrastructure.persistence.*;
import com.sajtech.identity.infrastructure.quota.*;
import com.sajtech.identity.infrastructure.security.externalidentity.*;
import com.sajtech.identity.interfaces.authentication.grpc.IdentityAuthenticationGrpcService;
import com.sajtech.identity.interfaces.externalidentity.grpc.IdentityExternalIdentityGrpcService;
import com.sajtech.identity.interfaces.mfa.grpc.IdentityMfaGrpcService;
import com.sajtech.identity.interfaces.password.grpc.IdentityPasswordGrpcService;
import com.sajtech.identity.interfaces.profile.grpc.IdentityProfileGrpcService;
import com.sajtech.identity.interfaces.registration.grpc.IdentityRegistrationGrpcService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!migration")
class IdentityAccountRuntimeConfiguration {
  @Bean
  RegisterLocalUseCase registerCore(
      IdentityProperties p,
      ContactCanonicalizer contacts,
      ProfileCanonicalizer profiles,
      PasswordNormalizer passwords,
      FingerprintMaterialEncoder encoder,
      IntentFingerprintPort fingerprints,
      IdempotencyGuard idempotency,
      SemanticQuotaPort quota,
      CompromisedPasswordPort compromised,
      PasswordHashPort hashes,
      @Qualifier("registrationChallengeSecrets") ChallengeSecretPort challenges,
      NotificationEscrowPort escrow,
      TransactionRunner tx,
      RegistrationStore store,
      Clock clock) {
    return new RegisterLocalUseCase(
        p.phoneRegistrationEnabled(),
        contacts,
        profiles,
        passwords,
        encoder,
        fingerprints,
        idempotency,
        quota,
        compromised,
        hashes,
        challenges,
        escrow,
        tx,
        store,
        clock);
  }

  @Bean
  ResendRegistrationVerificationUseCase resendCore(
      ContactCanonicalizer contacts,
      FingerprintMaterialEncoder encoder,
      IntentFingerprintPort fingerprints,
      IdempotencyGuard idempotency,
      SemanticQuotaPort quota,
      @Qualifier("registrationChallengeSecrets") ChallengeSecretPort challenges,
      NotificationEscrowPort escrow,
      TransactionRunner tx,
      RegistrationStore store,
      Clock clock) {
    return new ResendRegistrationVerificationUseCase(
        contacts, encoder, fingerprints, idempotency, quota, challenges, escrow, tx, store, clock);
  }

  @Bean
  ConfirmRegistrationUseCase confirmCore(
      ContactCanonicalizer contacts,
      FingerprintMaterialEncoder encoder,
      IntentFingerprintPort fingerprints,
      IdempotencyGuard idempotency,
      SemanticQuotaPort quota,
      @Qualifier("registrationChallengeSecrets") ChallengeSecretPort challenges,
      TransactionRunner tx,
      RegistrationStore store,
      Clock clock) {
    return new ConfirmRegistrationUseCase(
        contacts, encoder, fingerprints, idempotency, quota, challenges, tx, store, clock);
  }

  @Bean
  @Primary
  ObservedRegistration observedRegistration(
      RegisterLocalUseCase register,
      ResendRegistrationVerificationUseCase resend,
      ConfirmRegistrationUseCase confirm,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedRegistration(register, resend, confirm, observations, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "registration-runtime-enabled",
      havingValue = "true")
  IdentityRegistrationGrpcService registrationGrpc(ObservedRegistration observed) {
    return new IdentityRegistrationGrpcService(observed, observed, observed);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IdentityProfileGrpcService profileGrpc(ProfileManagement profileManagement) {
    return new IdentityProfileGrpcService(profileManagement);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  AuthenticateLocalUseCase authenticateLocalCore(
      ContactCanonicalizer contacts,
      PasswordNormalizer passwords,
      LoginQuotaPort quota,
      PasswordVerificationPort verifier,
      SessionCredentialPort credentials,
      TransactionRunner tx,
      AuthenticationStore store,
      AuthenticationTenantSelectionPort tenantSelection,
      MfaStore mfa,
      MfaCryptographyPort mfaCryptography,
      Clock clock) {
    return new AuthenticateLocalUseCase(
        contacts,
        passwords,
        quota,
        verifier,
        credentials,
        tx,
        store,
        tenantSelection,
        mfa,
        mfaCryptography,
        clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  MfaUseCase mfaCore(
      MfaStore mfa,
      MfaCryptographyPort cryptography,
      @Qualifier("loginQuotaCore") RedisLoginQuota quota,
      AuthenticationStore authentication,
      RefreshCredentialLookup refreshLookup,
      SessionCredentialPort credentials,
      AuthenticationTenantSelectionPort tenantSelection,
      TransactionRunner tx,
      Clock clock) {
    return new MfaUseCase(
        mfa,
        cryptography,
        quota,
        authentication,
        refreshLookup,
        credentials,
        tenantSelection,
        tx,
        clock);
  }

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ObservedMfa observedMfa(MfaUseCase mfa, ObservationRegistry observations, MeterRegistry meters) {
    return new ObservedMfa(mfa, mfa, observations, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IdentityMfaGrpcService mfaGrpc(ObservedMfa mfa) {
    return new IdentityMfaGrpcService(mfa, mfa);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ExternalIdentityUseCase externalIdentityCore(
      ExternalIdentityStore external,
      ExternalIdentityFingerprintPort fingerprints,
      ExternalIdentityResultCryptoPort resultCrypto,
      AuthenticationStore authentication,
      RefreshCredentialLookup refreshLookup,
      SessionCredentialPort credentials,
      AuthenticationTenantSelectionPort tenantSelection,
      LoginQuotaPort quota,
      MfaStore mfa,
      MfaCryptographyPort mfaCryptography,
      ContactCanonicalizer contacts,
      TransactionRunner tx,
      Clock clock) {
    return new ExternalIdentityUseCase(
        external,
        fingerprints,
        resultCrypto,
        authentication,
        refreshLookup,
        credentials,
        tenantSelection,
        quota,
        mfa,
        mfaCryptography,
        contacts,
        tx,
        clock);
  }

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ObservedExternalIdentity observedExternalIdentity(
      ExternalIdentityUseCase core, ObservationRegistry observations, MeterRegistry meters) {
    return new ObservedExternalIdentity(core, observations, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IdentityExternalIdentityGrpcService externalIdentityGrpc(ObservedExternalIdentity observed) {
    return new IdentityExternalIdentityGrpcService(observed);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  RefreshSessionUseCase refreshSessionCore(
      RefreshCredentialLookup lookup,
      SessionCredentialPort credentials,
      TransactionRunner tx,
      AuthenticationStore store,
      Clock clock) {
    return new RefreshSessionUseCase(lookup, credentials, tx, store, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  LogoutCurrentUseCase logoutCurrentCore(
      RefreshCredentialLookup lookup,
      TransactionRunner tx,
      AuthenticationStore store,
      Clock clock) {
    return new LogoutCurrentUseCase(lookup, tx, store, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  LogoutAllUseCase logoutAllCore(
      RefreshCredentialLookup lookup,
      TransactionRunner tx,
      AuthenticationStore store,
      Clock clock) {
    return new LogoutAllUseCase(lookup, tx, store, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IssueAudienceAccessTokenUseCase issueAudienceAccessTokenCore(
      IdentityProperties p,
      RefreshCredentialLookup lookup,
      TransactionRunner tx,
      AuthenticationStore store,
      com.sajtech.identity.application.authentication.port.out.TenantContextValidationPort tenants,
      AccessTokenSigner signer,
      Clock clock) {
    return new IssueAudienceAccessTokenUseCase(
        p.jwt().allowedAudiences(), lookup, tx, store, tenants, signer, clock);
  }

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ObservedAuthentication observedAuthentication(
      AuthenticateLocalUseCase authenticate,
      RefreshSessionUseCase refresh,
      LogoutCurrentUseCase logoutCurrent,
      LogoutAllUseCase logoutAll,
      IssueAudienceAccessTokenUseCase issueAccessToken,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedAuthentication(
        authenticate, refresh, logoutCurrent, logoutAll, issueAccessToken, observations, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IdentityAuthenticationGrpcService authenticationGrpc(ObservedAuthentication observed) {
    return new IdentityAuthenticationGrpcService(observed, observed, observed, observed, observed);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ChangePasswordUseCase changePasswordCore(
      AuthenticationStore store,
      SessionCredentialPort credentials,
      PasswordVerificationPort verifier,
      PasswordHashPort hashes,
      CompromisedPasswordPort compromised,
      PasswordNormalizer passwords,
      TransactionRunner tx,
      MfaStore mfa,
      Clock clock) {
    return new ChangePasswordUseCase(
        store, credentials, verifier, hashes, compromised, passwords, tx, mfa, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  RequestPasswordRecoveryUseCase requestPasswordRecoveryCore(
      PasswordRecoveryStore store,
      PasswordRecoverySecretPort secrets,
      NotificationEscrowPort escrow,
      ContactCanonicalizer contacts,
      SemanticQuotaPort quota,
      TransactionRunner tx,
      Clock clock) {
    return new RequestPasswordRecoveryUseCase(store, secrets, escrow, contacts, quota, tx, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ConfirmPasswordRecoveryUseCase confirmPasswordRecoveryCore(
      PasswordRecoveryStore recovery,
      PasswordRecoverySecretPort secrets,
      AuthenticationStore authentication,
      PasswordHashPort hashes,
      CompromisedPasswordPort compromised,
      PasswordNormalizer passwords,
      ContactCanonicalizer contacts,
      SemanticQuotaPort quota,
      TransactionRunner tx,
      MfaStore mfa,
      MfaCryptographyPort mfaCryptography,
      @Qualifier("loginQuotaCore") RedisLoginQuota mfaQuota,
      Clock clock) {
    return new ConfirmPasswordRecoveryUseCase(
        recovery,
        secrets,
        authentication,
        hashes,
        compromised,
        passwords,
        contacts,
        quota,
        tx,
        mfa,
        mfaCryptography,
        mfaQuota,
        clock);
  }

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ObservedPasswordLifecycle observedPasswordLifecycle(
      ChangePasswordUseCase change,
      RequestPasswordRecoveryUseCase request,
      ConfirmPasswordRecoveryUseCase confirm,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedPasswordLifecycle(change, request, confirm, observations, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IdentityPasswordGrpcService passwordGrpc(ObservedPasswordLifecycle password) {
    return new IdentityPasswordGrpcService(password, password, password);
  }
}
