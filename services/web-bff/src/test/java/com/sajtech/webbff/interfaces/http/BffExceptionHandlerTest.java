package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sajtech.webbff.application.BffError;
import com.sajtech.webbff.application.BffException;
import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import com.sajtech.webbff.application.port.out.TrustedClientAddressPort;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class BffExceptionHandlerTest {
  @Test
  void unavailableBootstrapReturnsStable503WithoutCookieOrExceptionDetails() throws Exception {
    var sessions = mock(BrowserSessionPort.class);
    when(sessions.bootstrap())
        .thenThrow(new BffException(BffError.DEPENDENCY_UNAVAILABLE, "sensitive-cause-canary"));
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new BrowserAuthController(
                    sessions,
                    mock(IdentityGateway.class),
                    mock(TrustedClientAddressPort.class),
                    Clock.systemUTC()))
            .setControllerAdvice(new BffExceptionHandler(sessions))
            .build();

    var result =
        mvc.perform(post("/api/v1/auth/session/bootstrap"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
            .andReturn();

    assertThat(result.getResponse().getContentAsString())
        .doesNotContain("sensitive-cause-canary", "csrfToken");
    verify(sessions).bootstrap();
    verifyNoMoreInteractions(sessions);
  }

  @Test
  void mapsUnknownResourceToBoundedNotFoundProblem() {
    var handler = new BffExceptionHandler(mock(BrowserSessionPort.class));
    var request = new MockHttpServletRequest("GET", "/missing");

    var response =
        handler.notFound(
            new NoResourceFoundException(HttpMethod.GET, "/", "No static resource"), request);

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody())
        .containsEntry("status", 404)
        .containsEntry("code", "NOT_FOUND")
        .containsEntry("type", "urn:hooshix:problem:not-found")
        .containsEntry("instance", "/missing");
  }

  @Test
  void registrationPreconditionIsNonEnumeratingConflictProblem() {
    var handler = new BffExceptionHandler(mock(BrowserSessionPort.class));
    var request = new MockHttpServletRequest("POST", "/api/v1/identity/registration");
    var response = new MockHttpServletResponse();

    var result =
        handler.bff(
            new BffException(BffError.REGISTRATION_REJECTED, "internal reason"), request, response);

    assertThat(result.getStatusCode().value()).isEqualTo(409);
    assertThat(result.getBody())
        .containsEntry("code", "REGISTRATION_REJECTED")
        .doesNotContainKey("detail");
  }
}
