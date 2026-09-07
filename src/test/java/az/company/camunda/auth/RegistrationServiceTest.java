package az.company.camunda.auth;

import az.company.camunda.exception.UpstreamUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final String USER_ID = "1f3c9a2e-0000-4000-8000-000000000001";
    private static final KeycloakUserClient.RealmRole VIEWER_ROLE =
            new KeycloakUserClient.RealmRole("role-id-viewer", "order-viewer");

    @Mock
    private KeycloakUserClient keycloakUserClient;

    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {
        registrationService = new RegistrationService(keycloakUserClient);
    }

    @Test
    void register_whenRequestIsValid_thenReturnTheCreatedUser() {
        // Given an identity provider that accepts the user
        when(keycloakUserClient.createUser(any())).thenReturn(USER_ID);
        when(keycloakUserClient.findRealmRole("order-viewer")).thenReturn(VIEWER_ROLE);

        // When the registration is processed
        RegistrationResponse response = registrationService.register(request());

        // Then the caller learns who was created
        assertThat(response.id()).isEqualTo(USER_ID);
        assertThat(response.username()).isEqualTo("dave");
    }

    @Test
    void register_whenRequestIsValid_thenAssignTheOrderViewerRole() {
        // Given an identity provider that accepts the user
        when(keycloakUserClient.createUser(any())).thenReturn(USER_ID);
        when(keycloakUserClient.findRealmRole("order-viewer")).thenReturn(VIEWER_ROLE);

        // When the registration is processed
        registrationService.register(request());

        // Then read-only access is granted by us, in this order - a role cannot be
        // assigned to a user that does not exist yet
        InOrder calls = inOrder(keycloakUserClient);
        calls.verify(keycloakUserClient).createUser(any());
        calls.verify(keycloakUserClient).assignRealmRole(USER_ID, VIEWER_ROLE);
    }

    @Test
    void register_whenUsernameIsTaken_thenThrowConflict() {
        // Given a username somebody already holds
        when(keycloakUserClient.createUser(any())).thenThrow(new DuplicateUsernameException("dave"));

        // When the registration is processed
        // Then it fails as a conflict and no role is handed out
        assertThatThrownBy(() -> registrationService.register(request()))
                .isInstanceOf(DuplicateUsernameException.class)
                .hasMessageContaining("dave");
        verify(keycloakUserClient, never()).assignRealmRole(any(), any());
    }

    @Test
    void register_whenRoleAssignmentFails_thenDoNotReportSuccess() {
        // Given a user that is created but cannot be granted the role
        when(keycloakUserClient.createUser(any())).thenReturn(USER_ID);
        when(keycloakUserClient.findRealmRole("order-viewer")).thenReturn(VIEWER_ROLE);
        UpstreamUnavailableException failure =
                new UpstreamUnavailableException("IDENTITY_PROVIDER_UNAVAILABLE", "unavailable");
        doThrow(failure).when(keycloakUserClient).assignRealmRole(USER_ID, VIEWER_ROLE);

        // When the registration is processed
        // Then the failure surfaces - a user with no role can log in and then see
        // 403 on every page, which must not be reported as a successful sign-up
        assertThatThrownBy(() -> registrationService.register(request()))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    @Test
    void register_whenIdentityProviderIsUnavailable_thenPropagateTheUpstreamFailure() {
        // Given an identity provider that cannot be reached
        when(keycloakUserClient.createUser(any()))
                .thenThrow(new UpstreamUnavailableException("IDENTITY_PROVIDER_UNAVAILABLE", "unavailable"));

        // When the registration is processed
        // Then it is not turned into a client error
        assertThatThrownBy(() -> registrationService.register(request()))
                .isInstanceOf(UpstreamUnavailableException.class);
    }

    private RegistrationRequest request() {
        return new RegistrationRequest("dave", "dave@example.com", "Dave", "Devlin", "s3cret-passphrase");
    }
}
