package ca.vm.parcelflow.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "ParcelFlow Tracking Service API",
                version = "0.1.0",
                description = """
                        Shipment tracking and carrier event processing.

                        ParcelFlow is an independent portfolio and training project. It is not \
                        affiliated with or based on proprietary systems from any delivery \
                        carriers or ecommerce retailers. Carrier codes are fictional.""",
                license = @License(name = "MIT")),
        servers = @Server(url = "http://localhost:8080", description = "Local"))
public class OpenApiConfiguration {
}
