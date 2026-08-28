package com.pdk.business.zhibo.live.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaMtxPropertiesTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void enabledMediaServiceRejectsShortSharedSecret() {
        MediaMtxProperties properties = new MediaMtxProperties();
        properties.setEnabled(true);
        properties.setInternalServiceToken("too-short");

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void enabledMediaServiceAcceptsSecureConfiguration() {
        MediaMtxProperties properties = new MediaMtxProperties();
        properties.setEnabled(true);
        properties.setInternalServiceToken("0123456789abcdef0123456789abcdef");

        assertThat(validator.validate(properties)).isEmpty();
    }
}
