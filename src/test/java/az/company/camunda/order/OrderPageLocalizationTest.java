package az.company.camunda.order;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo UI is the surface where the language is chosen, so the two things
 * worth pinning are that {@code ?lang=} actually reaches the bundles and that
 * the choice survives the next click - a switcher that forgets on redirect is
 * indistinguishable from one that never worked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderPageLocalizationTest {

    private static final String SPANISH_TITLE = "Demostración de Correlación de Camunda";
    private static final String AZERBAIJANI_TITLE = "Camunda Korrelyasiya Demosu";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderService orderService;

    @Test
    void index_whenNoLanguageIsRequested_thenRendersAzerbaijani() throws Exception {
        // Given no cookie and no Accept-Language header

        // When
        // Then
        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(AZERBAIJANI_TITLE)));
    }

    @Test
    void index_whenLanguageParameterIsSpanish_thenRendersSpanish() throws Exception {
        // Given the Spanish bundle is on the classpath

        // When
        // Then
        mockMvc.perform(get("/orders").param("lang", "es"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(SPANISH_TITLE)))
                .andExpect(content().string(containsString("Nombre del cliente")));
    }

    @Test
    void index_whenLanguageParameterIsSpanish_thenWritesTheLanguageCookie() throws Exception {
        // Given
        // When
        // Then
        mockMvc.perform(get("/orders").param("lang", "es"))
                .andExpect(cookie().value(CookieLocaleResolver.DEFAULT_COOKIE_NAME, "es"));
    }

    @Test
    void index_whenTheLanguageCookieIsPresent_thenRendersThatLanguageWithoutTheParameter() throws Exception {
        // Given the language was picked on an earlier request
        Cookie languageCookie = new Cookie(CookieLocaleResolver.DEFAULT_COOKIE_NAME, "es");

        // When
        // Then
        mockMvc.perform(get("/orders").cookie(languageCookie))
                .andExpect(content().string(containsString(SPANISH_TITLE)));
    }

    @Test
    void index_whenLanguageParameterIsUnsupported_thenFallsBackToAzerbaijani() throws Exception {
        // Given French has no bundle

        // When
        // Then
        mockMvc.perform(get("/orders").param("lang", "fr"))
                .andExpect(content().string(containsString(AZERBAIJANI_TITLE)));
    }

    @Test
    void index_whenLanguageParameterIsUnsupported_thenClearsTheLanguageCookieInsteadOfStoringIt() throws Exception {
        // Given French has no bundle

        // When
        MvcResult actualResult = mockMvc.perform(get("/orders").param("lang", "fr"))
                .andReturn();

        // Then
        Cookie actualCookie = actualResult.getResponse().getCookie(CookieLocaleResolver.DEFAULT_COOKIE_NAME);
        assertThat(actualCookie).isNotNull();
        assertThat(actualCookie.getValue()).doesNotContain("fr");
    }

    @Test
    void index_whenAnOrderIsAwaitingPayment_thenTranslatesItsStatus() throws Exception {
        // Given
        createOrder("Localized Status Customer");

        // When
        // Then
        mockMvc.perform(get("/orders").param("lang", "es"))
                .andExpect(content().string(containsString("Pendiente de pago")));
    }

    @Test
    void index_whenAnOrderIsAwaitingPayment_thenKeepsTheBadgeClassKeyedOnTheEnumName() throws Exception {
        // Given
        createOrder("Badge Class Customer");

        // When
        // Then
        mockMvc.perform(get("/orders").param("lang", "es"))
                .andExpect(content().string(containsString("badge-AWAITING_PAYMENT")));
    }

    @Test
    void index_whenTheDateFormatDiffersByLanguage_thenUsesTheFormatOfTheRequestedLanguage() throws Exception {
        // Given a Spanish bundle whose date pattern is dd/MM/yyyy
        createOrder("Date Format Customer");

        // When
        String actualContent = mockMvc.perform(get("/orders").param("lang", "es"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Then
        assertThat(actualContent).containsPattern("<td>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}:\\d{2}</td>");
    }

    @Test
    void index_whenMoreThanOnePageExists_thenTranslatesThePaginationInfo() throws Exception {
        // Given more orders than fit on a page of one
        createOrder("Paginated Customer One");
        createOrder("Paginated Customer Two");

        // When
        // Then
        mockMvc.perform(get("/orders").param("size", "1").param("lang", "es"))
                .andExpect(content().string(containsString("Página 1 de")))
                .andExpect(content().string(containsString("Siguiente")));
    }

    @Test
    void index_whenPaginationParametersArePresent_thenTheLanguageSwitcherPreservesThem() throws Exception {
        // Given
        // When
        // Then
        mockMvc.perform(get("/orders").param("page", "0").param("size", "5"))
                .andExpect(content().string(containsString("size=5&amp;lang=en")));
    }

    @Test
    void index_whenAFlashMessageCodeIsPresent_thenRendersItTranslatedWithItsArguments() throws Exception {
        // Given a redirect handed over a message code rather than finished text
        // When
        // Then
        mockMvc.perform(get("/orders")
                        .param("lang", "es")
                        .flashAttr("messageCode", "order.message.deleted")
                        .flashAttr("messageArgs", new Object[]{"abc-123"}))
                .andExpect(content().string(containsString("El pedido abc-123 se ha eliminado")));
    }

    @Test
    void create_whenAnOrderIsCreated_thenFlashesAMessageCodeInsteadOfPresentationText() throws Exception {
        // Given
        // When
        MvcResult actualResult = mockMvc.perform(post("/orders")
                        .with(csrf())
                        .param("customerName", "Flash Message Customer")
                        .param("amount", "10"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // Then
        assertThat(actualResult.getFlashMap().get("messageCode")).isEqualTo("order.message.created");
    }

    @Test
    void delete_whenTheOrderIsAlreadyGone_thenFlashesTheAlreadyGoneMessageCode() throws Exception {
        // Given
        // When
        MvcResult actualResult = mockMvc.perform(post("/orders/{correlationId}/delete", "no-such-correlation-id")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        // Then
        assertThat(actualResult.getFlashMap().get("messageCode")).isEqualTo("order.message.alreadyGone");
    }

    /** Amount stays below the DMN's HIGH threshold so the instance parks at the payment wait. */
    private void createOrder(String customerName) {
        orderService.createOrder(customerName, BigDecimal.TEN);
    }
}
