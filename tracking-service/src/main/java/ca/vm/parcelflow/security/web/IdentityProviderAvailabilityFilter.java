package ca.vm.parcelflow.security.web;

import ca.vm.parcelflow.security.DiscoveringClientRegistrationRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers a login attempt made while the identity provider is unreachable with a redirect back to
 * the UI's login page ({@code /login?error=idp_unavailable}), rather than letting Spring's
 * authorization request filter fail with a bare 500.
 *
 * <p>This is presentation only. Without a client registration there is no way to authenticate at
 * all; there is no fallback mechanism for this filter to fall back to.
 */
public class IdentityProviderAvailabilityFilter extends OncePerRequestFilter {

    static final String UNAVAILABLE_REDIRECT = "/login?error=idp_unavailable";

    private final DiscoveringClientRegistrationRepository repository;
    private final String authorizationRequestUri;

    public IdentityProviderAvailabilityFilter(
            DiscoveringClientRegistrationRepository repository, String authorizationRequestUri) {
        this.repository = repository;
        this.authorizationRequestUri = authorizationRequestUri;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !authorizationRequestUri.equals(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!repository.isAvailable()) {
            response.sendRedirect(request.getContextPath() + UNAVAILABLE_REDIRECT);
            return;
        }
        chain.doFilter(request, response);
    }
}
