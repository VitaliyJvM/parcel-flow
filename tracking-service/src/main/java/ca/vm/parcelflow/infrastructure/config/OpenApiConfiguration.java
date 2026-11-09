package ca.vm.parcelflow.infrastructure.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * The browser session is the only credential the API accepts, so it is the only security scheme.
 * Documented as a cookie "API key" because that is what OpenAPI offers for a session cookie; it is
 * set by the OIDC login and is HttpOnly, so no client ever handles its value.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "ParcelFlow Tracking Service API",
                version = "0.1.0",
                description = """
                        Shipment tracking and carrier event processing.

                        **Authentication.** Every `/api/**` endpoint except `/api/auth/*` requires \
                        a browser session established by the OIDC login \
                        (`GET /oauth2/authorization/keycloak`). Unauthenticated calls get 401, \
                        forbidden ones 403, both as `application/problem+json`. State-changing \
                        requests also need the `X-XSRF-TOKEN` header carrying the session's CSRF \
                        token from `GET /api/auth/csrf`.

                        **Retailer isolation.** A RETAILER sees only their own retailer's \
                        shipments; another retailer's shipment answers 404 exactly as a \
                        nonexistent one does. ADMIN sees every retailer and the `/api/admin` \
                        endpoints.

                        ParcelFlow is an independent portfolio and training project. It is not \
                        affiliated with or based on proprietary systems from any delivery \
                        carriers or ecommerce retailers. Carrier codes are fictional.""",
                license = @License(name = "MIT")),
        servers = @Server(url = "http://localhost:8080", description = "Local"),
        security = @SecurityRequirement(name = "browserSession"))
@SecurityScheme(
        name = "browserSession",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = "PARCELFLOW_SESSION",
        description = """
                HttpOnly session cookie set by the OIDC login. Named `__Host-parcelflow-session` \
                in production configurations.""")
public class OpenApiConfiguration {
}
