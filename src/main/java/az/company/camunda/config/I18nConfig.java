package az.company.camunda.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Locale resolution for both surfaces of the application.
 *
 * <p>Spring uses exactly one {@link LocaleResolver} per DispatcherServlet, and
 * the two surfaces want different things from it: the Thymeleaf UI needs the
 * language picked with {@code ?lang=} to survive the next click, while the REST
 * API sends no cookie at all and expects its {@code Accept-Language} header to
 * be honoured. A plain {@code CookieLocaleResolver} would serve the UI and make
 * the API ignore the header; a plain {@code AcceptHeaderLocaleResolver} would do
 * the reverse. Combining them - cookie first, header as the fallback - is what
 * lets one bean serve both.
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    /**
     * Azerbaijani is the default bundle ({@code messages.properties}) rather
     * than a {@code messages_az.properties}, so there is exactly one copy of
     * every Azerbaijani string.
     */
    static final Locale DEFAULT_LOCALE = Locale.of("az");

    static final String LANGUAGE_PARAMETER = "lang";

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("az", "en", "es");

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver localeResolver = new CookieLocaleResolver() {
            @Override
            protected Locale parseLocaleValue(String localeValue) {
                return supportedOrNull(super.parseLocaleValue(localeValue));
            }
        };
        localeResolver.setDefaultLocaleFunction(I18nConfig::preferredSupportedLocale);
        return localeResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
    }

    /**
     * Rejecting an unsupported {@code ?lang=} here rather than storing it keeps
     * the cookie clean: returning {@code null} makes the interceptor clear the
     * cookie and fall back to the default, instead of parking a language the
     * application has no bundle for.
     */
    private LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor localeChangeInterceptor = new LocaleChangeInterceptor() {
            @Override
            protected Locale parseLocaleValue(String localeValue) {
                return supportedOrNull(super.parseLocaleValue(localeValue));
            }
        };
        localeChangeInterceptor.setParamName(LANGUAGE_PARAMETER);
        return localeChangeInterceptor;
    }

    /**
     * The region is deliberately dropped: the application ships three language
     * bundles, not regional variants, so keeping {@code es-MX} would only make
     * the rendered date format depend on which browser sent the request.
     *
     * @return the supported language, or {@code null} so callers can fall back
     */
    private static Locale supportedOrNull(Locale locale) {
        if (locale == null || !SUPPORTED_LANGUAGES.contains(locale.getLanguage())) {
            return null;
        }
        return Locale.of(locale.getLanguage());
    }

    /**
     * The header is checked before the parsed locales because a servlet request
     * without {@code Accept-Language} still reports the server's own default
     * locale - which would quietly make the JVM's environment, rather than this
     * method, decide the language.
     */
    private static Locale preferredSupportedLocale(HttpServletRequest request) {
        if (!StringUtils.hasText(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE))) {
            return DEFAULT_LOCALE;
        }

        return Collections.list(request.getLocales()).stream()
                .map(I18nConfig::supportedOrNull)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(DEFAULT_LOCALE);
    }
}
