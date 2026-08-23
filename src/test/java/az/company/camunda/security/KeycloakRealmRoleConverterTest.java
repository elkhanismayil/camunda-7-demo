package az.company.camunda.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping is unit tested rather than exercised through an HTTP call
 * because this is where the bug everyone hits actually lives: the token is
 * valid, the signature verifies, and the roles still do not arrive.
 */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    void mapsRealmRolesToPrefixedAuthorities() {
        Jwt token = tokenWith(Map.of(
                "realm_access", Map.of("roles", List.of("order-admin", "order-viewer")),
                "scope", "email profile"));

        assertThat(converter.convert(token))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_order-admin", "ROLE_order-viewer");
    }

    /**
     * The exact shape of a token from a user with no realm roles. It must
     * produce no authorities rather than blowing up - an authenticated caller
     * with nothing granted is a 403, not a 500.
     */
    @Test
    void yieldsNoAuthoritiesWhenTheTokenCarriesNoRealmRoles() {
        assertThat(converter.convert(tokenWith(Map.of("scope", "email profile")))).isEmpty();
        assertThat(converter.convert(tokenWith(Map.of("realm_access", Map.of())))).isEmpty();
    }

    /**
     * Documents why the custom converter exists at all: everything Spring's
     * default converter would look at is in "scope", and none of the roles are.
     */
    @Test
    void ignoresTheScopeClaimEntirely() {
        Jwt token = tokenWith(Map.of(
                "realm_access", Map.of("roles", List.of("order-viewer")),
                "scope", "email profile order-admin"));

        assertThat(converter.convert(token))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_order-viewer");
    }

    private Jwt tokenWith(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("alice");
        claims.forEach(builder::claim);
        return builder.build();
    }
}
