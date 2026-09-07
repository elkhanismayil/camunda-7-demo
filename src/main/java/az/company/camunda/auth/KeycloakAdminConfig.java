package az.company.camunda.auth;

import az.company.camunda.exception.UpstreamUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class KeycloakAdminConfig {

    private static final String REGISTRAR_REGISTRATION_ID = "keycloak-registrar";
    private static final String UNAVAILABLE_CODE = "IDENTITY_PROVIDER_UNAVAILABLE";

    // Service-account flow: the application authenticates as itself, outside any
    // user's request. The request-scoped manager would look for a logged-in user,
    // and registration is by definition anonymous.
    @Bean
    OAuth2AuthorizedClientManager keycloakAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService) {

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    RestClient keycloakAdminRestClient(OAuth2AuthorizedClientManager keycloakAuthorizedClientManager,
                                       @Value("${keycloak.admin.base-url}") String baseUrl,
                                       @Value("${keycloak.admin.connect-timeout}") Duration connectTimeout,
                                       @Value("${keycloak.admin.read-timeout}") Duration readTimeout) {

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(bearerTokenInterceptor(keycloakAuthorizedClientManager))
                .build();
    }

    @Bean
    KeycloakUserClient keycloakUserClient(RestClient keycloakAdminRestClient,
                                          @Value("${keycloak.admin.realm}") String realm) {
        return new KeycloakUserClient(keycloakAdminRestClient, realm);
    }

    private ClientHttpRequestInterceptor bearerTokenInterceptor(OAuth2AuthorizedClientManager clientManager) {
        return (request, body, execution) -> {
            OAuth2AuthorizedClient authorizedClient = authorize(clientManager);
            request.getHeaders().setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
            return execution.execute(request, body);
        };
    }

    private OAuth2AuthorizedClient authorize(OAuth2AuthorizedClientManager clientManager) {
        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                .withClientRegistrationId(REGISTRAR_REGISTRATION_ID)
                // Client credentials identify the application, not a person, but the
                // manager still keys the cached token by a principal name.
                .principal(REGISTRAR_REGISTRATION_ID)
                .build();

        OAuth2AuthorizedClient authorizedClient;
        try {
            authorizedClient = clientManager.authorize(authorizeRequest);
        } catch (OAuth2AuthorizationException exception) {
            throw new UpstreamUnavailableException(UNAVAILABLE_CODE,
                    "The identity provider could not be reached while authenticating this service.", exception);
        }

        if (authorizedClient == null) {
            throw new UpstreamUnavailableException(UNAVAILABLE_CODE,
                    "The identity provider could not be reached while authenticating this service.");
        }
        return authorizedClient;
    }
}
