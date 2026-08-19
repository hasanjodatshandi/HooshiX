package com.sajtech.webbff.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class BffExceptionHandlerTest {
  @Test
  void mapsUnknownResourceToBoundedNotFoundProblem() {
    var handler = new BffExceptionHandler(mock(BrowserSessionPort.class));

    var response =
        handler.notFound(new NoResourceFoundException(HttpMethod.GET, "/", "No static resource"));

    assertThat(response.getStatusCode().value()).isEqualTo(404);
    assertThat(response.getBody()).containsEntry("status", 404).containsEntry("code", "NOT_FOUND");
  }
}
