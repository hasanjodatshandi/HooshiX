package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sajtech.webbff.application.BffError;
import com.sajtech.webbff.application.BffException;
import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class BffExceptionHandlerTest {
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
