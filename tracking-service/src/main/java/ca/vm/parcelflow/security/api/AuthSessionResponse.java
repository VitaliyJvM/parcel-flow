package ca.vm.parcelflow.security.api;

import ca.vm.parcelflow.security.CurrentUser;
import ca.vm.parcelflow.security.ParcelFlowRole;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the UI may know about its session. Identity and authorization facts only: no token, no
 * token expiry, no session id, no claim beyond the ones listed.
 */
@Schema(description = "The browser session's authentication state")
public record AuthSessionResponse(
        boolean authenticated,

        @Schema(description = "The signed-in user; absent when not authenticated",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        SessionUser user) {

    public static AuthSessionResponse anonymous() {
        return new AuthSessionResponse(false, null);
    }

    public static AuthSessionResponse of(CurrentUser user) {
        return new AuthSessionResponse(true, new SessionUser(
                user.subject(), user.username(), user.displayName(), user.role(), user.retailerId()));
    }

    @Schema(description = "The signed-in user")
    public record SessionUser(
            @Schema(description = "Stable identifier at the identity provider (OIDC sub)")
            String subject,

            @Schema(example = "retailer.alpha")
            String username,

            @Schema(example = "Alex Alpha")
            String displayName,

            ParcelFlowRole role,

            @Schema(description = """
                    The only retailer a RETAILER may act for, assigned in the identity provider. \
                    Absent for ADMIN, who may act for any retailer.""",
                    example = "alpha-goods",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String retailerId) {
    }
}
