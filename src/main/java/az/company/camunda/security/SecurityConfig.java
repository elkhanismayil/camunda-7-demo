package az.company.camunda.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The REST API is an OAuth2 resource server: it validates a bearer token
 * against Keycloak's published keys and authorizes on the roles inside it. It
 * never sees a password and never talks to Keycloak per request - the
 * signature and the {@code iss}/{@code exp} claims are enough, which is what
 * makes this scale without an auth round trip on every call.
 *
 * <p>Three chains rather than one, because the three surfaces have genuinely
 * different requirements and collapsing them would mean weakening all of them
 * to the weakest.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The API. Stateless bearer-token auth, so there is no session and no
     * cookie for an attacker to ride - which is exactly why CSRF protection is
     * switched off here and nowhere else.
     */
    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        return http
                .securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/orders").hasRole("order-admin")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/payment").hasRole("order-admin")
                        .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAnyRole("order-admin", "order-viewer")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * The Camunda webapps ship their own login and their own CSRF filter.
     * Layering Spring Security's CSRF on top would reject their POSTs, so this
     * chain stays out of the way. Securing Cockpit properly means OIDC SSO into
     * Camunda's identity service, which is a different job from protecting an
     * API - see the README.
     */
    @Bean
    @Order(2)
    SecurityFilterChain camundaWebappSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/camunda/**", "/app/**", "/lib/**", "/api/engine/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }

    /**
     * The Thymeleaf demo UI. Left open so the demo stays clickable, but CSRF
     * protection is deliberately kept: these are cookie-less session forms
     * today, and turning it off "because it is a demo" is how the habit forms.
     * Thymeleaf injects the token into every {@code th:action} form.
     */
    @Bean
    @Order(3)
    SecurityFilterChain uiSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }

    /**
     * Keycloak puts realm roles in {@code realm_access.roles}. Spring's default
     * converter reads {@code scope}/{@code scp} and would map alice's token to
     * {@code SCOPE_email}, {@code SCOPE_profile} and nothing else - every
     * hasRole check would fail against a perfectly valid token. This is the
     * single most common reason "my Keycloak roles do not work".
     *
     * <p>The ROLE_ prefix is what {@code hasRole("order-admin")} expands to.
     */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        converter.setPrincipalClaimName("preferred_username");
        return converter;
    }
}
