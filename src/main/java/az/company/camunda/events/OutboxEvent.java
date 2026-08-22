package az.company.camunda.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row of the transactional outbox.
 *
 * <p>The engine's state and this row live in the same database, so a delegate
 * that changes an order and records the corresponding event commits both or
 * neither. That is the whole point: publishing to Kafka from inside a delegate
 * would be a dual write - two systems, no shared transaction - and any crash
 * between the two leaves the process and the outside world disagreeing about
 * what happened.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The order's correlationId. Also used as the Kafka message key, so every
     * event for one order lands on the same partition and stays ordered.
     */
    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false, length = 2000)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    /** Null until the publisher has a broker acknowledgement in hand. */
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    public OutboxEvent(String aggregateId, String type, String payload) {
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    public void markPublished() {
        this.publishedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
