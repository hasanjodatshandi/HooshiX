package com.sajtech.webbff.interfaces.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class UnicodeCodePointSizeValidator
    implements ConstraintValidator<UnicodeCodePointSize, String> {
  private int min;
  private int max;

  @Override
  public void initialize(UnicodeCodePointSize annotation) {
    min = annotation.min();
    max = annotation.max();
    if (min < 0 || max < min) throw new IllegalArgumentException("Invalid code point bounds");
  }

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    if (value == null) return true;
    int length = value.codePointCount(0, value.length());
    return length >= min && length <= max;
  }
}
