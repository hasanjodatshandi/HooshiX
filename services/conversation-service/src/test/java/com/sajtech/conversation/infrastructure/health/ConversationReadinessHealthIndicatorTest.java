package com.sajtech.conversation.infrastructure.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.port.out.ModelPolicyProvider;
import com.sajtech.conversation.infrastructure.provider.openai.FileBackedOpenAiModelProvider;
import com.sajtech.conversation.infrastructure.security.IdentityJwtVerifier;
import com.sajtech.conversation.infrastructure.security.keyring.FileBackedContentKeyRing;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class ConversationReadinessHealthIndicatorTest {
  private DataSource dataSource;
  private FileBackedContentKeyRing keyRing;
  private IdentityJwtVerifier jwtVerifier;
  private Connection connection;
  private PreparedStatement statement;
  private ResultSet result;
  private ModelPolicyProvider modelPolicy;
  private FileBackedOpenAiModelProvider modelProvider;

  @BeforeEach
  void setUp() throws Exception {
    dataSource = mock(DataSource.class);
    keyRing = mock(FileBackedContentKeyRing.class);
    jwtVerifier = mock(IdentityJwtVerifier.class);
    connection = mock(Connection.class);
    statement = mock(PreparedStatement.class);
    result = mock(ResultSet.class);
    modelPolicy = mock(ModelPolicyProvider.class);
    modelProvider = mock(FileBackedOpenAiModelProvider.class);
    when(keyRing.isFresh()).thenReturn(true);
    when(jwtVerifier.isFresh()).thenReturn(true);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.prepareStatement(anyString())).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(result);
  }

  @Test
  void reportsUpOnlyWhenKeyDatabaseAndSchemaAreAvailable() throws Exception {
    when(result.next()).thenReturn(true);
    when(result.getBoolean(1)).thenReturn(true);

    assertThat(indicator().health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void reportsDownForStaleKeyWithoutOpeningDatabaseConnection() {
    when(keyRing.isFresh()).thenReturn(false);

    var health = indicator().health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "content_key_unavailable");
    verifyNoInteractions(dataSource);
  }

  @Test
  void reportsDownForMissingSchemaOrDatabaseFailure() throws Exception {
    when(result.next()).thenReturn(true);
    when(result.getBoolean(1)).thenReturn(false);
    var missing = indicator().health();
    assertThat(missing.getStatus()).isEqualTo(Status.DOWN);
    assertThat(missing.getDetails()).containsEntry("reason", "schema_unavailable");

    reset(dataSource);
    when(dataSource.getConnection()).thenThrow(new SQLException("private test failure"));
    var unavailable = indicator().health();
    assertThat(unavailable.getStatus()).isEqualTo(Status.DOWN);
    assertThat(unavailable.getDetails()).containsEntry("reason", "database_unavailable");
  }

  @Test
  void reportsDownForStaleJwtVerifierWithoutOpeningDatabaseConnection() {
    when(jwtVerifier.isFresh()).thenReturn(false);

    var health = indicator().health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("reason", "identity_jwt_verifier_unavailable");
    verifyNoInteractions(dataSource);
  }

  @Test
  void enabledProviderRequiresApprovedPolicyAndCredentialWithoutContactingProvider()
      throws Exception {
    when(modelPolicy.requireApprovedPolicy())
        .thenThrow(
            new ConversationException(
                ConversationError.MODEL_EXECUTION_DISABLED, "Model execution is disabled"));

    var policyUnavailable = indicator(true).health();
    assertThat(policyUnavailable.getStatus()).isEqualTo(Status.DOWN);
    assertThat(policyUnavailable.getDetails()).containsEntry("reason", "model_policy_unavailable");
    verifyNoInteractions(dataSource, modelProvider);

    reset(modelPolicy);
    when(modelPolicy.requireApprovedPolicy()).thenReturn(mock());
    when(modelProvider.isConfigured()).thenReturn(false);
    var credentialUnavailable = indicator(true).health();
    assertThat(credentialUnavailable.getStatus()).isEqualTo(Status.DOWN);
    assertThat(credentialUnavailable.getDetails())
        .containsEntry("reason", "provider_credential_unavailable");
    verifyNoInteractions(dataSource);
  }

  private ConversationReadinessHealthIndicator indicator() {
    return indicator(false);
  }

  private ConversationReadinessHealthIndicator indicator(boolean providerEnabled) {
    return new ConversationReadinessHealthIndicator(
        dataSource, keyRing, jwtVerifier, providerEnabled, modelPolicy, modelProvider);
  }
}
