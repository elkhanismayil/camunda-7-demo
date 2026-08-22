package az.company.camunda.order;

import org.camunda.bpm.engine.ExternalTaskService;
import org.camunda.bpm.engine.externaltask.LockedExternalTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.List;
import java.util.Map;

/**
 * The worker side of the external task pattern.
 *
 * <p>A {@link org.camunda.bpm.engine.delegate.JavaDelegate} is called <em>by</em>
 * the engine: inside the engine's transaction, on a job executor thread, from a
 * class the engine has to be able to load. This runs the other way round. The
 * BPMN names a topic, not a bean, so the engine knows nothing about this class;
 * the worker decides when to ask for work, does it on its own thread, and only
 * then reports back. That is what lets workers be deployed, scaled and
 * restarted independently of the engine, and why a worker being down for an
 * hour breaks nothing - the instances simply wait.
 *
 * <p><b>On the transport.</b> Normally a worker polls {@code /engine-rest} over
 * HTTP via camunda-external-task-client, which is what makes it deployable
 * anywhere in any language. That is not possible on this stack: Camunda 7.24's
 * REST starter is Jersey-based and Spring Boot 4 removed Jersey support, so
 * there is no {@code /engine-rest} to poll. This worker therefore calls the
 * engine-side {@link ExternalTaskService} directly. The operations are
 * identical - {@code fetchAndLock}, {@code complete}, {@code handleFailure} are
 * exactly what the REST client wraps - so only the hop is missing, not the
 * pattern.
 */
@Component
@Profile("!test")
public class InvoiceWorker {

    private static final Logger log = LoggerFactory.getLogger(InvoiceWorker.class);

    private static final String TOPIC = "invoice-generation";
    private static final String WORKER_ID = "invoice-worker-1";
    private static final int MAX_TASKS_PER_FETCH = 10;
    private static final int INITIAL_RETRIES = 3;
    private static final long RETRY_BACKOFF_MS = 10_000L;

    /**
     * How long a fetched task stays locked to this worker. Same trade-off as
     * the job executor's lock-time-in-millis: it has to exceed the slowest
     * realistic invoice call, or a second worker picks up a task that is still
     * being worked on and the invoice is generated twice.
     */
    private static final long LOCK_DURATION_MS = 10_000L;

    private final ExternalTaskService externalTaskService;
    private final OrderRepository orderRepository;

    public InvoiceWorker(ExternalTaskService externalTaskService, OrderRepository orderRepository) {
        this.externalTaskService = externalTaskService;
        this.orderRepository = orderRepository;
    }

    @Scheduled(fixedDelayString = "${demo.invoice-worker.poll-interval-ms:2000}")
    public void pollForWork() {
        List<LockedExternalTask> tasks = externalTaskService.fetchAndLock(MAX_TASKS_PER_FETCH, WORKER_ID)
                .topic(TOPIC, LOCK_DURATION_MS)
                // A worker asks for the variables it needs by name. It never
                // gets a database handle, which is the point: its only contract
                // with the process is this payload and the completion call.
                .variables("correlationId")
                .execute();

        tasks.forEach(this::handle);
    }

    private void handle(LockedExternalTask task) {
        String correlationId = (String) task.getVariables().get("correlationId");

        try {
            String invoiceNumber = generateInvoice(correlationId);
            externalTaskService.complete(task.getId(), WORKER_ID, Map.of("invoiceNumber", invoiceNumber));
            log.info("Generated {} for order {}", invoiceNumber, correlationId);
        } catch (RuntimeException e) {
            // The retry policy lives here, not in the model. A delegate takes
            // its retries from failedJobRetryTimeCycle and the engine counts
            // them down; an external worker reports how many attempts remain
            // and how long to wait, so it can vary that per failure. Reaching
            // zero is what turns the failure into an incident.
            Integer remaining = task.getRetries();
            int retriesLeft = remaining == null ? INITIAL_RETRIES : remaining - 1;

            log.warn("Invoice generation failed for order {}, {} retries left", correlationId, retriesLeft, e);
            externalTaskService.handleFailure(task.getId(), WORKER_ID,
                    "Invoice generation failed for order " + correlationId,
                    e.toString(),
                    retriesLeft,
                    RETRY_BACKOFF_MS);
        }
    }

    private String generateInvoice(String correlationId) {
        Order order = orderRepository.findByCorrelationId(correlationId)
                .orElseThrow(() -> new IllegalStateException("No order for correlationId " + correlationId));

        String invoiceNumber = "INV-%d-%06d".formatted(Year.now().getValue(), order.getId());
        order.assignInvoice(invoiceNumber);
        orderRepository.save(order);

        return invoiceNumber;
    }
}
