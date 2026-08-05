package ca.vm.parcelflow.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the system clock as a bean.
 *
 * <p>Nothing in the domain calls {@code Instant.now()}. Injecting a {@link Clock} lets tests pin
 * time and assert ordering decisions exactly, which matters for out-of-order event handling where
 * the whole behaviour is a comparison between timestamps.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
