package az.company.camunda.order;

import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/**
 * Plain server-rendered (Thymeleaf) front page for the demo - no SPA needed.
 * Drives the exact same OrderService the REST API uses, so clicking around
 * here exercises the real Camunda message correlation.
 */
@Controller
@RequestMapping("/orders")
public class OrderViewController {

    private final OrderService orderService;

    public OrderViewController(OrderService orderService) {
        this.orderService = orderService;
    }

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    @GetMapping
    public String index(@RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
                         Model model) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        Page<Order> orderPage = orderService.findOrders(safePage, safeSize);

        model.addAttribute("orderPage", orderPage);
        model.addAttribute("size", safeSize);
        model.addAttribute("form", new CreateOrderForm());
        return "orders/index";
    }

    @PostMapping
    public String create(@ModelAttribute("form") CreateOrderForm form, RedirectAttributes redirectAttributes) {
        ProcessInstance instance = orderService.createOrder(form.getCustomerName(), form.getAmount());
        redirectAttributes.addFlashAttribute("message",
                "Order created - correlationId=" + instance.getBusinessKey() + " is now waiting for payment.");
        return "redirect:/orders";
    }

    @PostMapping("/{correlationId}/payment")
    public String notifyPayment(@PathVariable String correlationId, RedirectAttributes redirectAttributes) {
        boolean correlated = orderService.notifyPaymentReceived(correlationId);
        redirectAttributes.addFlashAttribute("message", correlated
                ? "Payment message correlated to order " + correlationId + " - process advanced."
                : "No process instance is waiting for correlationId=" + correlationId + " anymore.");
        return "redirect:/orders";
    }

    public static final class CreateOrderForm {
        private String customerName;
        private BigDecimal amount;

        public String getCustomerName() {
            return customerName;
        }

        public void setCustomerName(String customerName) {
            this.customerName = customerName;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }
    }
}
