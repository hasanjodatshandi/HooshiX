package com.sajtech.compromisedpassword.domain.lookup.valueobject;

import java.util.Objects;
import java.util.regex.Pattern;

public final class Sha1Prefix {
    private static final Pattern CANONICAL_PREFIX = Pattern.compile("[0-9A-F]{5}");
    private final int value;

    private Sha1Prefix(int value) {
        this.value = value;
    }

    public static Sha1Prefix parse(String value) {
        Objects.requireNonNull(value, "value");
        if (!CANONICAL_PREFIX.matcher(value).matches()) {
            throw new IllegalArgumentException("SHA-1 prefix must be five uppercase hexadecimal characters");
        }
        return new Sha1Prefix(Integer.parseInt(value, 16));
    }

    public int asInteger() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof Sha1Prefix that && value == that.value);
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }
}
