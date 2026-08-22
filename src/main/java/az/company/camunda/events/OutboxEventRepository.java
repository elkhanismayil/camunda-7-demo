package az.company.camunda.events;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Ordered by id so events for one order are published in the order they
     * were recorded. The publisher stops at the first failure for the same
     * reason - skipping ahead would reorder them.
     */
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    List<OutboxEvent> findByAggregateIdOrderByIdAsc(String aggregateId);
}
