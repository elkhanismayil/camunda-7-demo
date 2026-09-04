package az.company.camunda.config;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One resolver serves both surfaces, so its precedence is the whole contract:
 * the UI writes a cookie and expects it to stick, while the API sends no cookie
 * and expects {@code Accept-Language} to be honoured. A resolver that got this
 * wrong would silently pin every API response to whatever language the last
 * browser session picked.
 */
class I18nConfigTest {

    private static final String ACCEPT_LANGUAGE = "Accept-Language";

    private final LocaleResolver localeResolver = new I18nConfig().localeResolver();

    @Test
    void resolveLocale_whenNoCookieAndNoAcceptLanguageHeaderArePresent_thenUsesAzerbaijani() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();

        // When
        Locale actualLocale = localeResolver.resolveLocale(request);

        // Then
        assertThat(actualLocale.getLanguage()).isEqualTo("az");
    }

    @Test
    void resolveLocale_whenCookieIsAbsent_thenUsesAcceptLanguageHeader() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ACCEPT_LANGUAGE, "es-ES,es;q=0.9");

        // When
        Locale actualLocale = localeResolver.resolveLocale(request);

        // Then
        assertThat(actualLocale.getLanguage()).isEqualTo("es");
    }

    @Test
    void resolveLocale_whenCookieIsPresent_thenCookieWinsOverAcceptLanguageHeader() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ACCEPT_LANGUAGE, "es");
        request.setCookies(new Cookie(CookieLocaleResolver.DEFAULT_COOKIE_NAME, "en"));

        // When
        Locale actualLocale = localeResolver.resolveLocale(request);

        // Then
        assertThat(actualLocale.getLanguage()).isEqualTo("en");
    }

    @Test
    void resolveLocale_whenAcceptLanguageHeaderIsUnsupported_thenFallsBackToAzerbaijani() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ACCEPT_LANGUAGE, "fr-FR,fr;q=0.9");

        // When
        Locale actualLocale = localeResolver.resolveLocale(request);

        // Then
        assertThat(actualLocale.getLanguage()).isEqualTo("az");
    }

    @Test
    void resolveLocale_whenAcceptLanguageHeaderRanksAnUnsupportedLanguageFirst_thenUsesTheBestSupportedOne() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ACCEPT_LANGUAGE, "fr;q=0.9,es;q=0.8");

        // When
        Locale actualLocale = localeResolver.resolveLocale(request);

        // Then
        assertThat(actualLocale.getLanguage()).isEqualTo("es");
    }

    @Test
    void resolveLocale_whenCookieHoldsAnUnsupportedLanguage_thenFallsBackToAzerbaijani() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CookieLocaleResolver.DEFAULT_COOKIE_NAME, "fr"));

        // When
        Locale actualLocale = localeResolver.resolveLocale(request);

        // Then
        assertThat(actualLocale.getLanguage()).isEqualTo("az");
    }
}
