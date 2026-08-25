package com.sajtech.webbff.interfaces.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import org.junit.jupiter.api.Test;

class UnicodeCodePointSizeValidatorTest {
  @Test
  void countsUnicodeCodePointsInsteadOfUtf16CodeUnits() {
    var validator = new UnicodeCodePointSizeValidator();
    validator.initialize(bounds(1, 2));

    assertThat(validator.isValid("😀😀", null)).isTrue();
    assertThat(validator.isValid("😀😀😀", null)).isFalse();
  }

  private static UnicodeCodePointSize bounds(int min, int max) {
    return new UnicodeCodePointSize() {
      public int min() {
        return min;
      }

      public int max() {
        return max;
      }

      public String message() {
        return "";
      }

      public Class<?>[] groups() {
        return new Class<?>[0];
      }

      @SuppressWarnings("unchecked")
      public Class<? extends jakarta.validation.Payload>[] payload() {
        return new Class[0];
      }

      public Class<? extends Annotation> annotationType() {
        return UnicodeCodePointSize.class;
      }
    };
  }
}
