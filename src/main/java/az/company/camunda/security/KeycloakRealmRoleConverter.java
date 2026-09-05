package az.company.camunda.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;

/**
 * Maps Keycloak's realm roles onto Spring Security authorities.
 *
 * <p>A Keycloak access token carries realm roles nested under
 * {@code realm_access.roles}:
 *
 * <pre>
 * "realm_access": { "roles": ["order-admin"] },
 * "scope": "email profile"
 * </pre>
 *
 * <p>Spring's default converter looks at {@code scope}/{@code scp}, so out of
 * the box that token grants {@code SCOPE_email} and {@code SCOPE_profile} and
 * no roles at all - every authorization rule fails against a token that is
 * otherwise completely valid, which makes it look like a token problem rather
 * than a mapping problem.
 *
 * <p>Client roles, if you use them, live somewhere else again:
 * {@code resource_access.<clientId>.roles}.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        return KeycloakRealmRoles.toAuthorities(jwt.getClaims());
    }
}
