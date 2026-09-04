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
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);

        Page<Order> orderPage = orderService.findOrders(safePage, safeSize);

        model.addAttribute("orderPage", orderPage);
        model.addAttribute("size", safeSize);
        model.addAttribute("form", new CreateOrderForm());
        return "orders/index";
    }

    @PostMapping
    public String create(@ModelAttribute("form") CreateOrderForm form, RedirectAttributes redirectAttributes) {
        ProcessInstance instance = orderService.createOrder(form.getCustomerName(), form.getAmount());
        flashMessage(redirectAttributes, "order.message.created", instance.getBusinessKey());
        return "redirect:/orders";
    }

    @PostMapping("/{correlationId}/payment")
    public String notifyPayment(@PathVariable String correlationId, RedirectAttributes redirectAttributes) {
        boolean correlated = orderService.notifyPaymentReceived(correlationId);
        flashMessage(redirectAttributes,
                correlated ? "order.message.paymentCorrelated" : "order.message.paymentNotCorrelated",
                correlationId);
        return "redirect:/orders";
    }

    @PostMapping("/{correlationId}/delete")
    public String delete(@PathVariable String correlationId, RedirectAttributes redirectAttributes) {
        boolean deleted = orderService.softDelete(correlationId);
        flashMessage(redirectAttributes,
                deleted ? "order.message.deleted" : "order.message.alreadyGone",
                correlationId);
        return "redirect:/orders";
    }

    /**
     * Hands the view a message code and its arguments rather than a finished
     * sentence. The redirect that follows is a separate request, and the reader
     * may have switched language in between - resolving the text here would
     * freeze it to the language of the POST.
     */
    private static void flashMessage(RedirectAttributes redirectAttributes, String messageCode, Object... messageArgs) {
        redirectAttributes.addFlashAttribute("messageCode", messageCode);
        redirectAttributes.addFlashAttribute("messageArgs", messageArgs);
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
