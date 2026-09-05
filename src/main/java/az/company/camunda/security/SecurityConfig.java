package az.company.camunda.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;

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
                        // Someone without an account cannot present a token to ask for one.
                        .requestMatchers(HttpMethod.POST, "/api/v1/registrations").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/orders").hasRole("order-admin")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/payment").hasRole("order-admin")
                        .requestMatchers(HttpMethod.DELETE, "/api/orders/*").hasRole("order-admin")
                        .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAnyRole("order-admin", "order-viewer")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
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
     * The Thymeleaf demo UI. CSRF protection is deliberately kept: these are
     * session forms, and turning it off "because it is a demo" is how the habit
     * forms. Thymeleaf injects the token into every {@code th:action} form.
     */
    @Bean
    @Order(3)
    SecurityFilterChain uiSecurityFilterChain(HttpSecurity http,
                                              GrantedAuthoritiesMapper realmRoleAuthoritiesMapper,
                                              LogoutSuccessHandler oidcLogoutSuccessHandler) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/register", "/error").permitAll()
                        // Before the GET rule below, which would otherwise also
                        // match these paths and hand a viewer write access.
                        .requestMatchers(HttpMethod.POST, "/orders").hasRole("order-admin")
                        .requestMatchers(HttpMethod.POST, "/orders/*/payment").hasRole("order-admin")
                        .requestMatchers(HttpMethod.POST, "/orders/*/delete").hasRole("order-admin")
                        .requestMatchers(HttpMethod.GET, "/orders/**").hasAnyRole("order-admin", "order-viewer")
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo.userAuthoritiesMapper(realmRoleAuthoritiesMapper)))
                .logout(logout -> logout.logoutSuccessHandler(oidcLogoutSuccessHandler))
                .build();
    }

    @Bean
    GrantedAuthoritiesMapper realmRoleAuthoritiesMapper() {
        return new KeycloakOidcRealmRoleMapper();
    }

    /**
     * Ending our own session is only half of a logout: without the round trip to
     * Keycloak the SSO session survives, and the next login silently succeeds
     * without asking for anything - which looks exactly like logout being broken.
     */
    @Bean
    LogoutSuccessHandler oidcLogoutSuccessHandler(ClientRegistrationRepository clientRegistrationRepository) {
        OidcClientInitiatedLogoutSuccessHandler logoutSuccessHandler =
                new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        // Templated rather than literal so this does not have to be re-edited per
        // environment; it resolves to the host the request arrived on, and must
        // match a post.logout.redirect.uris entry on the client in the realm file.
        logoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/");
        return logoutSuccessHandler;
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
