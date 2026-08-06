package ca.vm.parcelflow.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Command-line producer of synthetic carrier tracking events.
 *
 * <pre>
 * java -jar carrier-simulator.jar \
 *   --shipment-id=00c5356b-8b0b-47e5-b88c-1e504dd2bf34 \
 *   --tracking-number=SP100000000042 \
 *   --carrier=SWIFTPOST \
 *   --scenario=NORMAL
 * </pre>
 *
 * <p>Publishes each carrier's own event codes, never normalized ones. Normalizing here would mean
 * the tracking service's normalization layer was never exercised end to end.
 *
 * <p>There is no web starter on the classpath, so Boot starts this as a plain application: the
 * runner executes, the context closes, and the process exits. Status 0 on success, 1 on a usage or
 * publishing error, so it is usable from a script or {@code docker compose run}.
 */
@SpringBootApplication
public class CarrierSimulatorApplication implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CarrierSimulatorApplication.class);

    private final TrackingEventPublisher publisher;

    private int exitCode;

    public CarrierSimulatorApplication(TrackingEventPublisher publisher) {
        this.publisher = publisher;
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext context =
                new SpringApplication(CarrierSimulatorApplication.class).run(args);
        // Closes the context first, which flushes and closes the Kafka producer, then reports the
        // exit code this runner recorded.
        System.exit(SpringApplication.exit(context));
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }

    @Override
    public void run(ApplicationArguments args) {
        SimulationRequest request;
        try {
            request = SimulationRequest.parse(args);
        } catch (IllegalArgumentException e) {
            log.error("{}\n\n{}", e.getMessage(), SimulationRequest.USAGE);
            exitCode = 1;
            return;
        }

        log.info("Scenario={} ({}) carrier={} shipmentId={} trackingNumber={} delay={}ms seed={}",
                request.scenario(), request.scenario().description(), request.carrierCode(),
                request.shipmentId(), request.trackingNumber(),
                request.delayBetweenEvents().toMillis(), request.seed());

        try {
            publisher.publish(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while publishing; some events may not have been sent");
            exitCode = 1;
        } catch (RuntimeException e) {
            log.error("Failed to publish carrier events", e);
            exitCode = 1;
        }
    }
}
