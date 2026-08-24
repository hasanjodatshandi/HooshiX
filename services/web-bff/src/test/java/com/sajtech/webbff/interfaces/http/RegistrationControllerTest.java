package com.sajtech.webbff.interfaces.http;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sajtech.webbff.application.port.out.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RegistrationControllerTest {
  @Test
  void registerResendAndConfirmUseTrustedAddressAndReviewedHttpStatuses() throws Exception {
    IdentityGateway identity = mock(IdentityGateway.class);
    TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    byte[] client = {(byte) 192, 0, 2, 44};
    when(addresses.parse("192.0.2.44")).thenReturn(client);
    when(identity.register(
            any(),
            eq("EMAIL"),
            eq("person@example.com"),
            eq("Strong password"),
            eq("en"),
            eq("First"),
            eq("Last"),
            isNull(),
            same(client)))
        .thenReturn(new IdentityGateway.RegisterResult(true));
    when(identity.resendRegistration(any(), eq("EMAIL"), eq("person@example.com"), same(client)))
        .thenReturn(true);
    when(identity.confirmRegistration(
            any(), eq("EMAIL"), eq("person@example.com"), eq("12345678"), same(client)))
        .thenReturn(true);
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new RegistrationController(identity, addresses))
            .setControllerAdvice(new BffExceptionHandler(sessions))
            .build();

    String requestId = UUID.randomUUID().toString();
    mvc.perform(
            post("/api/v1/identity/registration")
                .header("X-Request-Id", requestId)
                .header("X-HooshiX-Client-IP", "192.0.2.44")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"channel\":\"EMAIL\",\"contact\":\"person@example.com\",\"password\":\"Strong password\",\"locale\":\"en\",\"firstName\":\"First\",\"lastName\":\"Last\"}"))
        .andExpect(status().isAccepted())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.accepted").value(true));

    mvc.perform(
            post("/api/v1/identity/registration/resend")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-HooshiX-Client-IP", "192.0.2.44")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"EMAIL\",\"contact\":\"person@example.com\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.accepted").value(true));

    mvc.perform(
            post("/api/v1/identity/registration/confirm")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-HooshiX-Client-IP", "192.0.2.44")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"channel\":\"EMAIL\",\"contact\":\"person@example.com\",\"code\":\"12345678\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.confirmed").value(true));
  }

  @Test
  void invalidPublicPayloadUsesRfc9457ProblemContract() throws Exception {
    IdentityGateway identity = mock(IdentityGateway.class);
    TrustedClientAddressPort addresses = mock(TrustedClientAddressPort.class);
    BrowserSessionPort sessions = mock(BrowserSessionPort.class);
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new RegistrationController(identity, addresses))
            .setControllerAdvice(new BffExceptionHandler(sessions))
            .build();

    mvc.perform(
            post("/api/v1/identity/registration/confirm")
                .header("X-Request-Id", UUID.randomUUID().toString())
                .header("X-HooshiX-Client-IP", "192.0.2.44")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"channel\":\"EMAIL\",\"contact\":\"person@example.com\",\"code\":\"12\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("urn:hooshix:problem:invalid-request"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        .andExpect(jsonPath("$.instance").value("/api/v1/identity/registration/confirm"));
    verifyNoInteractions(identity);
  }
}
