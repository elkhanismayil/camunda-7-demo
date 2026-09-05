package az.company.camunda.order;

import az.company.camunda.exception.ErrorResponse;
import az.company.camunda.exception.OrderNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Demonstrates Camunda message correlation: many "order-payment-correlation"
 * process instances can be running concurrently, each waiting at the
 * "PaymentReceived" message catch event. Correlation lets an external
 * notification (POST .../payment) reach the exact one waiting instance by
 * matching a business correlationId, without the caller knowing the process
 * instance id.
 */
@Tag(name = "Orders", description = "Creates orders and correlates payment messages to their process instances")
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final String NOT_FOUND_DESCRIPTION =
            "No live order answers to this correlation id - it never existed, was soft-deleted, "
                    + "or its process instance is no longer waiting";
    private static final String UNAUTHORIZED_DESCRIPTION = "No bearer token, or the token failed validation";
    private static final String FORBIDDEN_DESCRIPTION = "The token is valid but lacks the required realm role";

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "Create an order and start its process instance",
            description = "Returns the generated correlation id, which is also the process instance business key.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order created and the process instance started"),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION, content = @Content),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION, content = @Content)
    })
    @PostMapping
    public Map<String, String> createOrder(@RequestBody CreateOrderRequest request) {
        ProcessInstance instance = orderService.createOrder(request.customerName(), request.amount());

        return Map.of(
                "correlationId", instance.getBusinessKey(),
                "processInstanceId", instance.getId()
        );
    }

    @Operation(summary = "Correlate a PaymentReceived message to the waiting process instance")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Message correlated and the process advanced"),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION, content = @Content),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION, content = @Content),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{correlationId}/payment")
    public ResponseEntity<Void> notifyPaymentReceived(
            @Parameter(description = "Business correlation id returned when the order was created")
            @PathVariable String correlationId) {
        if (!orderService.notifyPaymentReceived(correlationId)) {
            throw new OrderNotFoundException(correlationId);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Soft delete: the row survives, but the order stops existing as far as
     * this API is concerned - hence 404 on everything afterwards, including a
     * second delete.
     */
    @Operation(summary = "Soft-delete an order and terminate its process instance")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Order hidden and every process instance terminated"),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION, content = @Content),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION, content = @Content),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{correlationId}")
    public ResponseEntity<Void> deleteOrder(
            @Parameter(description = "Business correlation id of the order to delete")
            @PathVariable String correlationId) {
        if (!orderService.softDelete(correlationId)) {
            throw new OrderNotFoundException(correlationId);
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Look up a single order by its correlation id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The order"),
            @ApiResponse(responseCode = "401", description = UNAUTHORIZED_DESCRIPTION, content = @Content),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_DESCRIPTION, content = @Content),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DESCRIPTION,
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{correlationId}")
    public ResponseEntity<Order> getOrder(
            @Parameter(description = "Business correlation id of the order to read")
            @PathVariable String correlationId) {
        return orderService.findByCorrelationId(correlationId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new OrderNotFoundException(correlationId));
    }

    public record CreateOrderRequest(
            @Schema(description = "Customer the order is placed for", example = "Ayla Mammadova")
            String customerName,

            @Schema(description = "Order amount; the DMN table routes high amounts to manual review", example = "42.00")
            BigDecimal amount) {
    }
}
