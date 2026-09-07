package az.company.camunda.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakOidcRealmRoleMapperTest {

    private final KeycloakOidcRealmRoleMapper mapper = new KeycloakOidcRealmRoleMapper();

    @Test
    void mapAuthorities_whenIdTokenCarriesRealmRoles_thenGrantPrefixedRoles() {
        // Given an ID token shaped the way Keycloak issues one once the realm-roles
        // mapper has id.token.claim enabled
        Collection<GrantedAuthority> authorities = List.of(authorityFor(Map.of(
                "sub", "bob",
                "realm_access", Map.of("roles", List.of("order-viewer")))));

        // When the login authorities are mapped
        Collection<? extends GrantedAuthority> actualAuthorities = mapper.mapAuthorities(authorities);

        // Then the realm role is usable by hasRole("order-viewer")
        assertThat(actualAuthorities)
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_order-viewer");
    }

    @Test
    void mapAuthorities_whenIdTokenOmitsRealmAccess_thenGrantNoRoles() {
        // Given the ID token of a user with no realm roles at all
        Collection<GrantedAuthority> authorities = List.of(authorityFor(Map.of("sub", "carol")));

        // When the login authorities are mapped
        Collection<? extends GrantedAuthority> actualAuthorities = mapper.mapAuthorities(authorities);

        // Then nothing is granted - an authenticated user with no roles is a 403, not a 500
        assertThat(actualAuthorities)
                .extracting(GrantedAuthority::getAuthority)
                .noneMatch(authority -> authority.startsWith("ROLE_"));
    }

    @Test
    void mapAuthorities_whenAuthorityDidNotComeFromAnIdToken_thenLeaveItInPlace() {
        // Given the scope authorities that accompany a login
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("SCOPE_openid"));

        // When the login authorities are mapped
        Collection<? extends GrantedAuthority> actualAuthorities = mapper.mapAuthorities(authorities);

        // Then mapping roles does not silently discard what was already granted
        assertThat(actualAuthorities)
                .extracting(GrantedAuthority::getAuthority)
                .contains("SCOPE_openid");
    }

    private OidcUserAuthority authorityFor(Map<String, Object> claims) {
        OidcIdToken idToken = new OidcIdToken("id-token", Instant.now(), Instant.now().plusSeconds(300), claims);
        return new OidcUserAuthority(idToken);
    }
}
