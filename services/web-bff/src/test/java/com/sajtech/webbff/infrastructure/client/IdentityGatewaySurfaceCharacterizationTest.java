package com.sajtech.webbff.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.webbff.application.port.out.IdentityGateway;
import io.grpc.ManagedChannel;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IdentityGatewaySurfaceCharacterizationTest {
  @Test
  void facadePreservesTheCompleteIdentityGatewayPortAndConstructor() throws Exception {
    assertThat(IdentityGateway.class).isAssignableFrom(IdentityBffClient.class);
    assertThat(IdentityBffClient.class.getConstructor(ManagedChannel.class)).isNotNull();
    assertThat(publicInstanceSignatures(IdentityBffClient.class))
        .containsAll(publicInstanceSignatures(IdentityGateway.class));
  }

  private static Set<String> publicInstanceSignatures(Class<?> type) {
    return Arrays.stream(type.getMethods())
        .filter(method -> method.getDeclaringClass() != Object.class)
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .filter(method -> !Modifier.isStatic(method.getModifiers()))
        .map(IdentityGatewaySurfaceCharacterizationTest::signature)
        .collect(Collectors.toUnmodifiableSet());
  }

  private static String signature(Method method) {
    return method.getName()
        + Arrays.stream(method.getParameterTypes())
            .map(Class::getName)
            .collect(Collectors.joining(",", "(", ")"))
        + ":"
        + method.getReturnType().getName();
  }
}
