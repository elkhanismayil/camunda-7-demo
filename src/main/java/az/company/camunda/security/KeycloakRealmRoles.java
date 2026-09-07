package az.company.camunda.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Map;

final class KeycloakRealmRoles {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    private KeycloakRealmRoles() {
    }

    static Collection<GrantedAuthority> toAuthorities(Map<String, Object> claims) {
        if (claims == null || !(claims.get(REALM_ACCESS_CLAIM) instanceof Map<?, ?> realmAccess)) {
            return List.of();
        }

        if (!(realmAccess.get(ROLES_KEY) instanceof Collection<?> roles)) {
            return List.of();
        }

        return roles.stream()
                .map(String::valueOf)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
    }
}
