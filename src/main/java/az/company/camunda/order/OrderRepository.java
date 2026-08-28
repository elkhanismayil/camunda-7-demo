package az.company.camunda.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Two flavours of read on purpose. The {@code DeletedAtIsNull} methods are what
 * the API and the UI use, so a soft-deleted order disappears from every user
 * facing surface. The unfiltered ones stay for the delegates and the tests,
 * which sometimes have to see the row that was hidden.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByCorrelationId(String correlationId);

    Optional<Order> findByCorrelationIdAndDeletedAtIsNull(String correlationId);

    Page<Order> findAllByDeletedAtIsNull(Pageable pageable);
}
