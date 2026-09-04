package az.company.camunda.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code MessageBundleParityTest} guarantees every key exists in the default
 * (Azerbaijani) bundle, so a lookup for an existing key never falls through to
 * the JVM's own locale - which makes {@code fallback-to-system-locale: false}
 * look untested from the outside. It still matters for the case that parity
 * test does not cover: a key genuinely missing from the default bundle. With
 * the fallback left on, that lookup would silently render in whatever locale
 * the host JVM runs in rather than failing loudly. Pinning the bound property
 * value keeps that flag from being quietly reverted.
 */
@SpringBootTest
@ActiveProfiles("test")
class MessageSourceConfigPropertiesTest {

    @Value("${spring.messages.fallback-to-system-locale}")
    private boolean fallbackToSystemLocale;

    @Test
    void fallbackToSystemLocaleProperty_whenApplicationStarts_thenIsDisabled() {
        // Given the application context has bound spring.messages.fallback-to-system-locale

        // When
        boolean actualFallbackToSystemLocale = fallbackToSystemLocale;

        // Then
        assertThat(actualFallbackToSystemLocale).isFalse();
    }
}
