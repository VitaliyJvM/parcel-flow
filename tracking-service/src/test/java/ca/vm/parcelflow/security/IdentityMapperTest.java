package ca.vm.parcelflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.vm.parcelflow.security.IdentityMappingException.Reason;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The fail-closed identity mapping. Every malformed identity must be refused with a reason, never
 * mapped to a default role or a default retailer.
 */
class IdentityMapperTest {

    private static Map<String, Object> claims(Object roles, Object retailerId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "0d2b6c9e-7a14-4f3b-8e25-1c9f4a6d2b02");
        claims.put("preferred_username", "retailer.alpha");
        claims.put("name", "Alex Alpha");
        if (roles != null) {
            claims.put("roles", roles);
        }
        if (retailerId != null) {
            claims.put("retailer_id", retailerId);
        }
        return claims;
    }

    private static void assertRefused(Map<String, Object> claims, Reason reason) {
        assertThatThrownBy(() -> IdentityMapper.fromClaims(claims))
                .isInstanceOf(IdentityMappingException.class)
                .extracting(e -> ((IdentityMappingException) e).getReason())
                .isEqualTo(reason);
    }

    @Nested
    @DisplayName("accepted identities")
    class Accepted {

        @Test
        @DisplayName("a retailer with exactly one valid assignment, ignoring Keycloak's default roles")
        void retailer() {
            CurrentUser user = IdentityMapper.fromClaims(claims(
                    List.of("offline_access", "RETAILER", "default-roles-parcelflow"), List.of("alpha-goods")));

            assertThat(user.role()).isEqualTo(ParcelFlowRole.RETAILER);
            assertThat(user.retailerId()).isEqualTo("alpha-goods");
            assertThat(user.subject()).isEqualTo("0d2b6c9e-7a14-4f3b-8e25-1c9f4a6d2b02");
            assertThat(user.username()).isEqualTo("retailer.alpha");
            assertThat(user.displayName()).isEqualTo("Alex Alpha");
        }

        @Test
        @DisplayName("a single-valued retailer claim is accepted as well as a one-element array")
        void retailerAsPlainString() {
            assertThat(IdentityMapper.fromClaims(claims(List.of("RETAILER"), "beta-supplies")).retailerId())
                    .isEqualTo("beta-supplies");
        }

        @Test
        @DisplayName("an admin has no retailer restriction, and a stray retailer claim does not create one")
        void admin() {
            CurrentUser user = IdentityMapper.fromClaims(claims(List.of("ADMIN"), List.of("alpha-goods")));

            assertThat(user.isAdmin()).isTrue();
            assertThat(user.retailerId()).isNull();
            assertThat(user.canAccessRetailer("anyone")).isTrue();
        }

        @Test
        @DisplayName("the display name falls back to the username")
        void displayNameFallback() {
            Map<String, Object> claims = claims(List.of("ADMIN"), null);
            claims.remove("name");
            assertThat(IdentityMapper.fromClaims(claims).displayName()).isEqualTo("retailer.alpha");
        }
    }

    @Nested
    @DisplayName("refused identities (fail closed)")
    class Refused {

        @Test
        @DisplayName("no subject")
        void noSubject() {
            Map<String, Object> claims = claims(List.of("ADMIN"), null);
            claims.remove("sub");
            assertRefused(claims, Reason.MISSING_SUBJECT);
        }

        @Test
        @DisplayName("no roles claim at all")
        void noRolesClaim() {
            assertRefused(claims(null, List.of("alpha-goods")), Reason.NO_APPLICATION_ROLE);
        }

        @Test
        @DisplayName("only roles that are not ParcelFlow's, including a lower-case look-alike")
        void noApplicationRole() {
            assertRefused(claims(List.of("offline_access", "admin", "retailer", "uma_authorization"), null),
                    Reason.NO_APPLICATION_ROLE);
        }

        @Test
        @DisplayName("both ADMIN and RETAILER: ambiguous")
        void bothRoles() {
            assertRefused(claims(List.of("ADMIN", "RETAILER"), List.of("alpha-goods")),
                    Reason.MULTIPLE_APPLICATION_ROLES);
        }

        @Test
        @DisplayName("a retailer without an assignment")
        void retailerWithoutAssignment() {
            assertRefused(claims(List.of("RETAILER"), null), Reason.MISSING_RETAILER_ASSIGNMENT);
            assertRefused(claims(List.of("RETAILER"), List.of()), Reason.MISSING_RETAILER_ASSIGNMENT);
        }

        @Test
        @DisplayName("a retailer with two assignments: ambiguous, not 'the first one'")
        void retailerWithTwoAssignments() {
            assertRefused(claims(List.of("RETAILER"), List.of("alpha-goods", "beta-supplies")),
                    Reason.AMBIGUOUS_RETAILER_ASSIGNMENT);
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"", " ", "alpha goods", "-alpha", "alpha/goods", "alpha%2Fgoods",
                "alpha\ngoods", "../beta-supplies",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
        @DisplayName("a retailer whose assignment is malformed")
        void retailerWithMalformedAssignment(String retailerId) {
            assertRefused(claims(List.of("RETAILER"), List.of(retailerId)), Reason.INVALID_RETAILER_ASSIGNMENT);
        }

        @Test
        @DisplayName("a retailer whose assignment is not a string")
        void retailerWithNonStringAssignment() {
            assertRefused(claims(List.of("RETAILER"), List.of(42)), Reason.INVALID_RETAILER_ASSIGNMENT);
            assertRefused(claims(List.of("RETAILER"), Map.of("id", "alpha-goods")),
                    Reason.INVALID_RETAILER_ASSIGNMENT);
        }
    }

    @Nested
    @DisplayName("CurrentUser.canAccessRetailer — the isolation decision")
    class AccessDecision {

        private final CurrentUser alpha = new CurrentUser("s", "u", "d", ParcelFlowRole.RETAILER, "alpha-goods");

        @Test
        @DisplayName("a retailer may access only their own retailer, compared exactly")
        void retailerScope() {
            assertThat(alpha.canAccessRetailer("alpha-goods")).isTrue();
            assertThat(alpha.canAccessRetailer("beta-supplies")).isFalse();
            assertThat(alpha.canAccessRetailer("ALPHA-GOODS")).isFalse();
            assertThat(alpha.canAccessRetailer("alpha-goods ")).isFalse();
            assertThat(alpha.canAccessRetailer(null)).isFalse();
        }

        @Test
        @DisplayName("a retailer without a retailer cannot even be constructed")
        void retailerRequiresAssignment() {
            assertThatThrownBy(() -> new CurrentUser("s", "u", "d", ParcelFlowRole.RETAILER, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
