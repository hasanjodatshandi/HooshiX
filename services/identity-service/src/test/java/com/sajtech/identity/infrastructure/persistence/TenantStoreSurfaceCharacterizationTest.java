package com.sajtech.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.identity.application.authentication.port.out.AuthenticationTenantSelectionPort;
import com.sajtech.identity.application.authentication.port.out.TenantContextValidationPort;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.tenant.port.out.TenantStore;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

class TenantStoreSurfaceCharacterizationTest {
  @Test
  void facadePreservesAllThreeApplicationPortsAndConstructor() throws Exception {
    assertThat(TenantStore.class).isAssignableFrom(JooqTenantStore.class);
    assertThat(TenantContextValidationPort.class).isAssignableFrom(JooqTenantStore.class);
    assertThat(AuthenticationTenantSelectionPort.class).isAssignableFrom(JooqTenantStore.class);
    assertThat(JooqTenantStore.class.getConstructor(DSLContext.class, IntentFingerprintPort.class))
        .isNotNull();

    Set<String> facade = publicInstanceSignatures(JooqTenantStore.class);
    assertThat(facade).containsAll(publicInstanceSignatures(TenantStore.class));
    assertThat(facade).containsAll(publicInstanceSignatures(TenantContextValidationPort.class));
    assertThat(facade)
        .containsAll(publicInstanceSignatures(AuthenticationTenantSelectionPort.class));
  }

  private static Set<String> publicInstanceSignatures(Class<?> type) {
    return Arrays.stream(type.getMethods())
        .filter(method -> method.getDeclaringClass() != Object.class)
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .filter(method -> !Modifier.isStatic(method.getModifiers()))
        .map(TenantStoreSurfaceCharacterizationTest::signature)
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
