package com.sajtech.identity.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

class RuntimeConfigurationCharacterizationTest {
  @Test
  void composedRuntimeConfigurationRetainsCriticalBeanNamesConditionsAndLifecycle() {
    Set<Class<?>> configurationTypes = configurationTypes(RuntimeConfiguration.class);
    Set<Method> beanMethods = new HashSet<>();
    configurationTypes.forEach(
        type -> {
          assertThat(type.getAnnotation(Profile.class).value()).containsExactly("!migration");
          for (Method method : type.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class)) beanMethods.add(method);
          }
        });

    assertThat(beanMethods).hasSizeGreaterThanOrEqualTo(100);
    assertBean(beanMethods, "fingerprintKeyRing", "fingerprintKeyRing", "(inferred)");
    assertBean(
        beanMethods, "compromisedPasswordChannel", "compromisedPasswordChannel", "shutdownNow");
    assertBean(beanMethods, "authorizationChannel", "authorizationChannel", "shutdownNow");
    assertBean(beanMethods, "notificationChannel", "notificationChannel", "shutdownNow");
    assertBean(beanMethods, "readiness", "identityReadiness", "(inferred)");
    assertBean(
        beanMethods, "authenticationReadiness", "identityAuthenticationReadiness", "(inferred)");

    Method refreshKeyRing = method(beanMethods, "refreshKeyRing");
    ConditionalOnProperty condition = refreshKeyRing.getAnnotation(ConditionalOnProperty.class);
    assertThat(condition.prefix()).isEqualTo("identity");
    assertThat(condition.name()).containsExactly("authentication-runtime-enabled");
    assertThat(condition.havingValue()).isEqualTo("true");
  }

  private static Set<Class<?>> configurationTypes(Class<?> root) {
    Set<Class<?>> result = new HashSet<>();
    ArrayDeque<Class<?>> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      Class<?> type = pending.removeFirst();
      if (!result.add(type)) continue;
      Import imported = type.getAnnotation(Import.class);
      if (imported != null) pending.addAll(java.util.List.of(imported.value()));
    }
    return Set.copyOf(result);
  }

  private static void assertBean(
      Set<Method> methods, String methodName, String beanName, String destroyMethod) {
    Bean bean = method(methods, methodName).getAnnotation(Bean.class);
    Set<String> names = new HashSet<>();
    names.addAll(Set.of(bean.name()));
    names.addAll(Set.of(bean.value()));
    assertThat(names).contains(beanName);
    assertThat(bean.destroyMethod()).isEqualTo(destroyMethod);
  }

  private static Method method(Set<Method> methods, String name) {
    return methods.stream()
        .filter(method -> method.getName().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing bean method " + name));
  }
}
