package ca.vm.parcelflow.simulator;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean definitions for the simulator.
 *
 * <p>Separate from {@link CarrierSimulatorApplication} on purpose. Declaring {@code @Bean} methods
 * on the application class while that same class constructor-injects a component which depends on
 * one of those beans is a bean definition cycle, and Spring Boot rejects cycles by default.
 */
@Configuration
public class SimulatorConfiguration {

    /** UTC everywhere: the contract requires UTC event times. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
