package com.sajtech.compromisedpassword.domain.lookup.valueobject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Sha1PrefixTest {
    @Test
    void acceptsCanonicalUppercasePrefix() {
        assertThat(Sha1Prefix.parse("ABCDE").asInteger()).isEqualTo(0xABCDE);
    }

    @Test
    void rejectsLowercaseOrWrongLength() {
        assertThatThrownBy(() -> Sha1Prefix.parse("abcde"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sha1Prefix.parse("ABCD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Sha1Prefix.parse("ABCDEF"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
