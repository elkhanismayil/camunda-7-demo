package az.company.camunda;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs on the same in-memory profile as every other test, so the suite needs
 * neither Postgres nor Kafka to be up.
 */
@SpringBootTest
@ActiveProfiles("test")
class Camunda7DemoApplicationTests {

    @Test
    void contextLoads() {
    }

}
