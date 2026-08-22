package az.company.camunda;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling drives the external task worker's polling loop. */
@EnableScheduling
@SpringBootApplication
public class Camunda7DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(Camunda7DemoApplication.class, args);
    }

}
