package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ConversationControllerTest {
  @Test
  void acceptedRunUsesOnlyServerHeldRefreshAndConversationAudience() {
    IdentityGateway identity = mock(IdentityGateway.class);
    ConversationGateway conversations = mock(ConversationGateway.class);
    BrowserSession session = tenantSession();
    UUID conversationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    when(identity.issueAudienceToken(
            any(UUID.class), eq("server-refresh"), eq("conversation-service")))
        .thenReturn("audience-token");
    when(conversations.createRun(
            "audience-token", requestId, conversationId, "Explain this safely"))
        .thenReturn(
            new ConversationGateway.RunDto(
                runId,
                conversationId,
                "QUEUED",
                null,
                false,
                Instant.parse("2026-09-20T12:00:00Z"),
                null,
                null));

    var response =
        new ConversationController(identity, conversations)
            .createRun(
                requestId.toString(),
                conversationId.toString(),
                new ConversationController.CreateRun("Explain this safely"),
                request(session));

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    assertThat(response.getBody().runId()).isEqualTo(runId);
    assertThat(response.getBody().toString())
        .doesNotContain("server-refresh", "audience-token", "model", "price");
    verify(identity)
        .issueAudienceToken(any(UUID.class), eq("server-refresh"), eq("conversation-service"));
  }

  @Test
  void listUsesBoundedBrowserPaginationAndTenantSession() {
    IdentityGateway identity = mock(IdentityGateway.class);
    ConversationGateway conversations = mock(ConversationGateway.class);
    when(identity.issueAudienceToken(any(UUID.class), anyString(), eq("conversation-service")))
        .thenReturn("audience-token");
    when(conversations.list("audience-token", 20, "opaque"))
        .thenReturn(new ConversationGateway.ConversationPage(java.util.List.of(), ""));

    var result =
        new ConversationController(identity, conversations)
            .list(20, "opaque", request(tenantSession()));

    assertThat(result.conversations()).isEmpty();
    verify(conversations).list("audience-token", 20, "opaque");
  }

  private static MockHttpServletRequest request(BrowserSession session) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE, session);
    return request;
  }

  private static BrowserSession tenantSession() {
    Instant now = Instant.parse("2026-09-20T12:00:00Z");
    return new BrowserSession(
        "locator",
        BrowserSessionMode.TENANT_AUTHENTICATED,
        UUID.randomUUID(),
        "s".repeat(43),
        UUID.randomUUID(),
        "server-refresh",
        UUID.randomUUID(),
        UUID.randomUUID(),
        "k1",
        "0".repeat(64),
        now,
        now,
        now.plusSeconds(3600),
        now.plusSeconds(7200));
  }
}
