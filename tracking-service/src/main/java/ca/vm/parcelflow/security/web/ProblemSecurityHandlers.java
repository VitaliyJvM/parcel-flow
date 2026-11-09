package ca.vm.parcelflow.security.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Renders 401 and 403 decisions made inside the security filter chain through the same
 * {@code @RestControllerAdvice} that renders every other API error, so an API client sees one
 * RFC 9457 shape whether a request was refused by Spring Security, by method security, or by the
 * retailer-isolation check in a service.
 *
 * <p>The correlation id is already on the response: {@code CorrelationIdFilter} runs before the
 * security filter chain.
 *
 * <p>Applied to {@code /api/**} only. An ordinary browser navigation to a protected backend URL
 * still gets the standard OIDC login redirect.
 */
public final class ProblemSecurityHandlers {

    private ProblemSecurityHandlers() {
    }

    public static AuthenticationEntryPoint entryPoint(HandlerExceptionResolver resolver) {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException e) ->
                resolver.resolveException(request, response, null, e);
    }

    public static AccessDeniedHandler accessDeniedHandler(HandlerExceptionResolver resolver) {
        return (HttpServletRequest request, HttpServletResponse response, AccessDeniedException e) ->
                resolver.resolveException(request, response, null, e);
    }
}
