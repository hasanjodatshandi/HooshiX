package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.model.BrowserSession;
import com.sajtech.webbff.application.port.out.ConversationGateway;
import com.sajtech.webbff.application.port.out.ConversationGateway.*;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import com.sajtech.webbff.interfaces.validation.UnicodeCodePointSize;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!migration")
@RequestMapping("/api/v1/conversations")
public final class ConversationController {
  private final IdentityGateway identity;
  private final ConversationGateway conversations;

  public ConversationController(IdentityGateway identity, ConversationGateway conversations) {
    this.identity = identity;
    this.conversations = conversations;
  }

  @PostMapping
  public ResponseEntity<ConversationDto> create(
      @RequestHeader("Idempotency-Key") String requestId,
      @Valid @RequestBody CreateConversation body,
      HttpServletRequest request) {
    BrowserSession session = HttpSupport.tenant(request);
    return ResponseEntity.status(201)
        .body(
            conversations.create(
                token(session), HttpSupport.idempotencyKey(requestId), body.title()));
  }

  @GetMapping
  public ConversationPage list(
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
      @RequestParam(required = false) @Size(max = 256) String pageToken,
      HttpServletRequest request) {
    return conversations.list(token(HttpSupport.tenant(request)), pageSize, pageToken);
  }

  @GetMapping("/{conversationId}")
  public ConversationDto get(@PathVariable String conversationId, HttpServletRequest request) {
    return conversations.get(token(HttpSupport.tenant(request)), HttpSupport.id(conversationId));
  }

  @PostMapping("/{conversationId}/archive")
  public ConversationDto archive(
      @RequestHeader("Idempotency-Key") String requestId,
      @PathVariable String conversationId,
      @Valid @RequestBody VersionedMutation body,
      HttpServletRequest request) {
    return conversations.archive(
        token(HttpSupport.tenant(request)),
        HttpSupport.idempotencyKey(requestId),
        HttpSupport.id(conversationId),
        body.expectedVersion());
  }

  @DeleteMapping("/{conversationId}")
  public ResponseEntity<Void> delete(
      @RequestHeader("Idempotency-Key") String requestId,
      @PathVariable String conversationId,
      @RequestParam @Min(1) long expectedVersion,
      HttpServletRequest request) {
    conversations.delete(
        token(HttpSupport.tenant(request)),
        HttpSupport.idempotencyKey(requestId),
        HttpSupport.id(conversationId),
        expectedVersion);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{conversationId}/messages")
  public MessagePage messages(
      @PathVariable String conversationId,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
      @RequestParam(required = false) @Size(max = 256) String pageToken,
      HttpServletRequest request) {
    return conversations.messages(
        token(HttpSupport.tenant(request)), HttpSupport.id(conversationId), pageSize, pageToken);
  }

  @PostMapping("/{conversationId}/runs")
  public ResponseEntity<RunDto> createRun(
      @RequestHeader("Idempotency-Key") String requestId,
      @PathVariable String conversationId,
      @Valid @RequestBody CreateRun body,
      HttpServletRequest request) {
    return ResponseEntity.accepted()
        .body(
            conversations.createRun(
                token(HttpSupport.tenant(request)),
                HttpSupport.idempotencyKey(requestId),
                HttpSupport.id(conversationId),
                body.userMessage()));
  }

  @GetMapping("/{conversationId}/runs/{runId}")
  public RunDto getRun(
      @PathVariable String conversationId, @PathVariable String runId, HttpServletRequest request) {
    return conversations.getRun(
        token(HttpSupport.tenant(request)), HttpSupport.id(conversationId), HttpSupport.id(runId));
  }

  @PostMapping("/{conversationId}/runs/{runId}/cancel")
  public RunDto cancelRun(
      @RequestHeader("Idempotency-Key") String requestId,
      @PathVariable String conversationId,
      @PathVariable String runId,
      HttpServletRequest request) {
    return conversations.cancelRun(
        token(HttpSupport.tenant(request)),
        HttpSupport.idempotencyKey(requestId),
        HttpSupport.id(conversationId),
        HttpSupport.id(runId));
  }

  @PostMapping("/{conversationId}/runs/{runId}/feedback")
  public ResponseEntity<Void> submitFeedback(
      @RequestHeader("Idempotency-Key") String requestId,
      @PathVariable String conversationId,
      @PathVariable String runId,
      @Valid @RequestBody RunFeedback body,
      HttpServletRequest request) {
    conversations.submitFeedback(
        token(HttpSupport.tenant(request)),
        HttpSupport.idempotencyKey(requestId),
        HttpSupport.id(conversationId),
        HttpSupport.id(runId),
        body.value());
    return ResponseEntity.noContent().build();
  }

  private String token(BrowserSession session) {
    return identity.issueAudienceToken(
        UUID.randomUUID(), session.refreshCredential(), "conversation-service");
  }

  public record CreateConversation(
      @NotBlank
          @UnicodeCodePointSize(min = 1, max = 120)
          @Pattern(regexp = "^[^\\x00-\\x1F\\x7F]+$")
          String title) {}

  public record VersionedMutation(@Min(1) long expectedVersion) {}

  public record CreateRun(
      @NotBlank @UnicodeCodePointSize(min = 1, max = 16000) @Pattern(regexp = "^[^\\x00]+$")
          String userMessage) {}

  public record RunFeedback(@NotNull ConversationGateway.RunFeedbackValue value) {}
}
