package az.company.camunda.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A missing translation does not fail at startup - it silently falls back to
 * the default bundle, so the page renders Azerbaijani inside an otherwise
 * Spanish UI and nobody notices until a user complains. Comparing the key sets
 * turns that into a build failure.
 *
 * <p>There is deliberately no {@code messages_az.properties}: Azerbaijani is
 * the default bundle, so a second Azerbaijani file would be a duplicate that
 * could drift.
 */
class MessageBundleParityTest {

    private static final String DEFAULT_BUNDLE = "messages.properties";
    private static final String ENGLISH_BUNDLE = "messages_en.properties";
    private static final String SPANISH_BUNDLE = "messages_es.properties";

    @Test
    void defaultBundle_whenLoaded_thenIsNotEmpty() {
        // Given the default bundle is the fallback every other bundle is compared against

        // When
        Set<String> actualKeys = keysOf(DEFAULT_BUNDLE);

        // Then
        assertThat(actualKeys)
                .as("an empty default bundle would make every parity comparison pass for free")
                .isNotEmpty();
    }

    @Test
    void englishBundle_whenComparedWithTheDefaultBundle_thenCarriesTheSameKeys() {
        // Given
        Set<String> expectedKeys = keysOf(DEFAULT_BUNDLE);

        // When
        Set<String> actualKeys = keysOf(ENGLISH_BUNDLE);

        // Then
        assertThat(actualKeys).containsExactlyInAnyOrderElementsOf(expectedKeys);
    }

    @Test
    void spanishBundle_whenComparedWithTheDefaultBundle_thenCarriesTheSameKeys() {
        // Given
        Set<String> expectedKeys = keysOf(DEFAULT_BUNDLE);

        // When
        Set<String> actualKeys = keysOf(SPANISH_BUNDLE);

        // Then
        assertThat(actualKeys).containsExactlyInAnyOrderElementsOf(expectedKeys);
    }

    private static Set<String> keysOf(String bundleName) {
        try (InputStream bundle = MessageBundleParityTest.class.getClassLoader().getResourceAsStream(bundleName)) {
            assertThat(bundle).as("bundle %s must exist on the classpath", bundleName).isNotNull();

            Properties properties = new Properties();
            try (Reader reader = new InputStreamReader(bundle, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }

            return properties.stringPropertyNames().stream().collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read bundle " + bundleName, e);
        }
    }
}
