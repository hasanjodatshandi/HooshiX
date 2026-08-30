package com.sajtech.authorization.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.authorization.application.port.out.AuthorizationStore;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class AuthorizationStoreSurfaceCharacterizationTest {
  @Test
  void facadePreservesTheCompleteAuthorizationStorePortAndConstructor() throws Exception {
    assertThat(AuthorizationStore.class).isAssignableFrom(JooqAuthorizationStore.class);
    assertThat(
            JooqAuthorizationStore.class.getConstructor(
                DSLContext.class,
                com.sajtech.authorization.application.port.out.AuthorizationSecurityTelemetry
                    .class))
        .isNotNull();

    assertThat(publicInstanceSignatures(JooqAuthorizationStore.class))
        .containsAll(publicInstanceSignatures(AuthorizationStore.class));
  }

  private static Set<String> publicInstanceSignatures(Class<?> type) {
    return Arrays.stream(type.getMethods())
        .filter(method -> method.getDeclaringClass() != Object.class)
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .filter(method -> !Modifier.isStatic(method.getModifiers()))
        .map(AuthorizationStoreSurfaceCharacterizationTest::signature)
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
