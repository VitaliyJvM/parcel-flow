package ca.vm.parcelflow.security.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Spring Security's standard authorization request resolver — state, nonce and PKCE S256 are all
 * generated there — with one addition: {@code /oauth2/authorization/keycloak?prompt=login} forwards
 * {@code prompt=login} to the provider.
 *
 * <p>The UI uses it for the sign-in button shown after a logout or a refused login. RP-initiated
 * logout already ends the Keycloak SSO session, so normally nothing would log the user straight back
 * in; {@code prompt=login} makes that true even when the end-session redirect never completed (the
 * browser was closed mid-redirect, or Keycloak was unreachable at logout time) and covers the
 * refused-login case, where the user must be able to sign in as somebody else.
 *
 * <p>Only the literal value {@code login} is forwarded. Nothing else from the request reaches the
 * authorization request.
 */
public class LoginPromptAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String PROMPT_PARAMETER = "prompt";
    static final String PROMPT_LOGIN = "login";

    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public LoginPromptAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository, String authorizationRequestBaseUri) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, authorizationRequestBaseUri);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withPrompt(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withPrompt(request, delegate.resolve(request, clientRegistrationId));
    }

    private static OAuth2AuthorizationRequest withPrompt(
            HttpServletRequest request, OAuth2AuthorizationRequest authorizationRequest) {
        if (authorizationRequest == null || !PROMPT_LOGIN.equals(request.getParameter(PROMPT_PARAMETER))) {
            return authorizationRequest;
        }
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .additionalParameters(parameters -> parameters.put(PROMPT_PARAMETER, PROMPT_LOGIN))
                .build();
    }
}
