package ca.vm.parcelflow.security.api;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A CSRF token and where to send it")
public record CsrfTokenResponse(
        @Schema(example = "X-XSRF-TOKEN")
        String headerName,

        @Schema(example = "_csrf")
        String parameterName,

        @Schema(description = "Masked per response; any issued value stays valid for the session")
        String token) {
}
