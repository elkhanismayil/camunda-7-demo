package az.company.camunda.order;

import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Soft delete hides an order from every read path but keeps the row, so the
 * business history (what the order was, what it ended up as) survives. The
 * process instance is a different matter: leaving it running would let a
 * payment message arrive later and flip a "deleted" order to PAID, so deleting
 * the order terminates the instance too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderSoftDeleteTest {

    private static final String PROCESS_KEY = "order-payment-correlation";

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void hidesADeletedOrderFromLookupsButKeepsTheRow() {
        String deleted = createOrder("Deleted Customer");
        String kept = createOrder("Kept Customer");

        assertThat(orderService.softDelete(deleted)).isTrue();

        assertThat(orderService.findByCorrelationId(deleted)).isEmpty();
        assertThat(orderService.findByCorrelationId(kept))
                .as("deleting one order must not hide any other")
                .isPresent();

        Order row = findRowIncludingDeleted(deleted);
        assertThat(row.getDeletedAt()).isNotNull();
        assertThat(row.getStatus())
                .as("soft delete is orthogonal to the business status - it must not overwrite it")
                .isEqualTo(OrderStatus.AWAITING_PAYMENT);
    }

    @Test
    void keepsADeletedOrderOutOfTheListing() {
        String deleted = createOrder("Listed Then Deleted");
        String kept = createOrder("Still Listed");

        orderService.softDelete(deleted);

        // Newest first, and nothing else creates orders in between, so both
        // would be on the first page if they were still visible.
        assertThat(orderService.findOrders(0, 10).getContent())
                .extracting(Order::getCorrelationId)
                .contains(kept)
                .doesNotContain(deleted);
    }

    @Test
    void deletesEveryProcessInstanceSharingTheBusinessKey() {
        String correlationId = createOrder("Duplicated Business Key");

        // Camunda does not enforce unique business keys. Started before the
        // gateway so this second instance skips Task_CreateOrder and does not
        // try to insert a second order row.
        runtimeService.createProcessInstanceByKey(PROCESS_KEY)
                .businessKey(correlationId)
                .startBeforeActivity("Gateway_WaitForEvent")
                .setVariable("correlationId", correlationId)
                .execute();

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(correlationId)
                .count())
                .isEqualTo(2);

        assertThat(orderService.softDelete(correlationId)).isTrue();

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(correlationId)
                .count())
                .as("every instance behind the business key must be gone, not just the first")
                .isZero();
    }

    @Test
    void reportsFalseForAnUnknownOrder() {
        assertThat(orderService.softDelete("no-such-correlation-id")).isFalse();
    }

    @Test
    void reportsFalseWhenTheOrderIsAlreadyDeleted() {
        String correlationId = createOrder("Deleted Twice");

        assertThat(orderService.softDelete(correlationId)).isTrue();
        assertThat(orderService.softDelete(correlationId)).isFalse();
    }

    @Test
    void deletesAnOrderFromTheDemoUiAndDropsItOffThePage() throws Exception {
        String correlationId = createOrder("Deleted From The Ui");

        mockMvc.perform(post("/orders/{correlationId}/delete", correlationId).with(asOrderAdmin()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/orders"));

        mockMvc.perform(get("/orders").with(asOrderAdmin()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(correlationId))));
    }

    /** Deleting through the UI is an order-admin action; see OrderUiSecurityTest. */
    private static RequestPostProcessor asOrderAdmin() {
        return oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_order-admin"));
    }

    /** Amount stays below the DMN's HIGH threshold so the instance parks at the payment wait. */
    private String createOrder(String customerName) {
        return orderService.createOrder(customerName, BigDecimal.TEN).getBusinessKey();
    }

    private Order findRowIncludingDeleted(String correlationId) {
        return orderRepository.findAll().stream()
                .filter(order -> correlationId.equals(order.getCorrelationId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the row must survive a soft delete"));
    }
}
