package ca.vm.parcelflow.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every HTTP request a correlation id and puts it in the logging context.
 *
 * <p>Propagate if present, generate if not. A retailer calling the API can pass
 * {@code X-Correlation-Id} and then find ParcelFlow's log lines for their request using the id
 * their own system already recorded; a caller that passes nothing still gets one id shared by every
 * line the request produced.
 *
 * <p>The id is echoed back on the response so the caller can record it for a request they did not
 * tag themselves — which is the case that matters, because the request someone needs to trace is
 * usually the one nobody expected to have to trace.
 *
 * <p>Ordered first so that anything logging later in the chain — including the error handler
 * rendering a problem+json response — is already inside the context.
 *
 * <p>This is the HTTP half of correlation. The Kafka half is
 * {@code CarrierTrackingEventListener}, which takes the id off the message. The two are separate on
 * purpose: an event's correlation id belongs to the carrier's publish, not to whichever HTTP
 * request happened to be in flight.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId =
                LogContext.correlationIdOrNew(request.getHeader(CORRELATION_ID_HEADER));

        // Set before the chain runs, not in a wrapper around the response: the header has to be on
        // the response before anything commits it, and an error response commits early.
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try (var ignored = LogContext.forRequest(correlationId)) {
            filterChain.doFilter(request, response);
        }
    }

    /**
     * Actuator endpoints are excluded. A Prometheus scrape every five seconds would otherwise mint
     * a correlation id per scrape, and health checks are not units of work anyone traces.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }
}
