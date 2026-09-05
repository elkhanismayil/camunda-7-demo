package az.company.camunda.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

class KeycloakOidcRealmRoleMapper implements GrantedAuthoritiesMapper {

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> mappedAuthorities = new LinkedHashSet<>(authorities);

        authorities.stream()
                .filter(OidcUserAuthority.class::isInstance)
                .map(OidcUserAuthority.class::cast)
                .map(authority -> KeycloakRealmRoles.toAuthorities(authority.getIdToken().getClaims()))
                .forEach(mappedAuthorities::addAll);

        return mappedAuthorities;
    }
}
