package az.company.camunda.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The correlationId is what a Camunda message correlation targets at runtime -
 * it lets an external event (e.g. a payment webhook) find the exact waiting
 * process instance among many, without knowing the process instance id.
 */
@Entity
@Table(name = "demo_order")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String correlationId;

    private String customerName;

    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    /** Written by the external task worker, not by the engine itself. */
    private String invoiceNumber;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant deletedAt;

    protected Order() {
    }

    public Order(String correlationId, String customerName, BigDecimal amount) {
        this.correlationId = correlationId;
        this.customerName = customerName;
        this.amount = amount;
        this.status = OrderStatus.AWAITING_PAYMENT;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markPaid() {
        this.status = OrderStatus.PAID;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    public void refund() {
        this.status = OrderStatus.REFUNDED;
        this.updatedAt = Instant.now();
    }

    /**
     * Deliberately not an {@link OrderStatus} value: "deleted" is orthogonal to
     * where the order got to in the business flow, and folding it into the
     * status would erase whether the order was PAID or CANCELLED when it went.
     */
    public void markDeleted() {
        this.deletedAt = Instant.now();
        this.updatedAt = this.deletedAt;
    }

    public void assignInvoice(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
