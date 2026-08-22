package az.company.camunda.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@Profile("!test")
public class KafkaConfig {

    /**
     * Boot's auto-configured template is declared as {@code KafkaTemplate<?, ?>},
     * which will not satisfy an injection point asking for
     * {@code KafkaTemplate<String, String>}. Declaring it explicitly fixes the
     * type and makes Boot's own definition back off.
     */
    @Bean
    KafkaTemplate<String, String> kafkaTemplate(KafkaProperties kafkaProperties) {
        return new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties()));
    }

    /**
     * One partition keeps the demo readable. In a real deployment the partition
     * count is what bounds consumer parallelism, and keying by correlationId is
     * what keeps one order's events on one partition and therefore in order.
     */
    @Bean
    NewTopic paymentEventsTopic(@Value("${demo.kafka.payment-events-topic}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic orderEventsTopic(@Value("${demo.kafka.order-events-topic}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventsDeadLetterTopic(@Value("${demo.kafka.payment-events-dlt-topic}") String name) {
        return TopicBuilder.name(name).partitions(1).replicas(1).build();
    }

    /**
     * Retries in the consumer, then parks the record on {@code <topic>.DLT}.
     *
     * <p>Without this a failing record is retried forever and blocks its
     * partition - the whole queue stops behind one poison message. The backoff
     * exists because the most common failure here is a race that resolves
     * itself: the process simply has not reached the message event yet.
     *
     * <p>UnknownOrderException is excluded from retrying because no amount of
     * waiting will conjure up the order.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                          @Value("${demo.kafka.payment-events-dlt-topic}") String deadLetterTopic) {
        // The destination is named rather than left to the recoverer's default
        // suffix convention, so the topic declared above and the topic actually
        // written to cannot drift apart. Partition -1 lets the producer pick,
        // which matters as soon as the DLT has a different partition count from
        // the source topic.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> new TopicPartition(deadLetterTopic, -1));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3));

        errorHandler.addNotRetryableExceptions(PaymentEventConsumer.UnknownOrderException.class);

        return errorHandler;
    }
}
