package ca.vm.parcelflow.security.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * Idle and absolute session expiry, with a clock the test moves by hand: every boundary is asserted
 * to the second, nothing waits.
 */
class SessionLifetimeFilterTest {

    private static final Duration IDLE = Duration.ofMinutes(30);
    private static final Duration ABSOLUTE = Duration.ofHours(8);
    private static final Instant LOGIN = Instant.parse("2026-11-09T08:00:00Z");

    private final MutableClock clock = new MutableClock(LOGIN);
    private final SessionLifetimeFilter filter = new SessionLifetimeFilter(IDLE, ABSOLUTE, clock);
    private MockHttpSession session;

    @BeforeEach
    void login() {
        session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(new TestingAuthenticationToken("user", null, "ROLE_RETAILER")));
        MockHttpServletRequest loginRequest = new MockHttpServletRequest();
        loginRequest.setSession(session);
        filter.sessionStarted(loginRequest);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Sends one request through the filter; returns whether the session survived it. */
    private boolean request(boolean background) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/shipments/x");
        request.setSession(session);
        if (background) {
            request.addHeader(SessionLifetimeFilter.BACKGROUND_HEADER, "true");
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("the chain always continues").isNotNull();
        return !session.isInvalid();
    }

    @Test
    @DisplayName("login records the login time and counts as activity")
    void loginRecordsTimes() {
        assertThat(session.getAttribute(SessionLifetimeFilter.AUTHENTICATED_AT)).isEqualTo(LOGIN);
        assertThat(session.getAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT)).isEqualTo(LOGIN);
    }

    @Test
    @DisplayName("idle: alive at 29:59 after the last activity, ended at exactly 30:00")
    void idleBoundary() throws Exception {
        clock.advance(IDLE.minusSeconds(1));
        assertThat(request(false)).isTrue();
        assertThat(session.getAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT)).isEqualTo(clock.instant());

        clock.advance(IDLE.minusSeconds(1));
        assertThat(request(true)).as("29:59 after the previous activity").isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(request(true)).as("30:00 after the previous activity").isFalse();
    }

    @Test
    @DisplayName("idle: background polling every 15 s does not keep the session alive")
    void backgroundPollingDoesNotCount() throws Exception {
        Duration elapsed = Duration.ZERO;
        while (elapsed.compareTo(IDLE) < 0) {
            assertThat(request(true)).as("background poll at %s", elapsed).isTrue();
            assertThat(session.getAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT)).isEqualTo(LOGIN);
            clock.advance(Duration.ofSeconds(15));
            elapsed = elapsed.plusSeconds(15);
        }
        assertThat(request(true)).isFalse();
    }

    @Test
    @DisplayName("idle: a request without the background marker is activity and restarts the idle window")
    void foregroundRequestRestartsIdle() throws Exception {
        clock.advance(Duration.ofMinutes(25));
        assertThat(request(false)).isTrue();
        clock.advance(Duration.ofMinutes(25));
        assertThat(request(true)).as("50 min after login, 25 after the last activity").isTrue();
    }

    @Test
    @DisplayName("absolute: ends exactly 8 h after login even with user activity every 10 min")
    void absoluteLifetime() throws Exception {
        while (clock.instant().isBefore(LOGIN.plus(ABSOLUTE).minus(Duration.ofMinutes(10)))) {
            clock.advance(Duration.ofMinutes(10));
            assertThat(request(false)).as("active at %s", clock.instant()).isTrue();
        }
        clock.set(LOGIN.plus(ABSOLUTE).minusSeconds(1));
        assertThat(request(false)).as("7:59:59 after login").isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(request(false)).as("8:00:00 after login").isFalse();
    }

    @Test
    @DisplayName("ending a session clears the security context for the rest of the request")
    void endingClearsContext() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("user", null));
        clock.advance(ABSOLUTE);
        assertThat(request(false)).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("never unbounded: a session with no lifetime record gets one on first sight; both limits apply")
    void missingRecordStartsTheClock() throws Exception {
        session.removeAttribute(SessionLifetimeFilter.AUTHENTICATED_AT);
        session.removeAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT);
        clock.advance(Duration.ofHours(1));
        Instant firstSeen = clock.instant();
        assertThat(request(true)).isTrue();
        assertThat(session.getAttribute(SessionLifetimeFilter.AUTHENTICATED_AT)).isEqualTo(firstSeen);
        assertThat(session.getAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT)).isEqualTo(firstSeen);

        clock.set(firstSeen.plus(IDLE));
        assertThat(request(true)).as("idle limit from first sight").isFalse();
    }

    @Test
    @DisplayName("fail closed: a missing login time with a recorded activity ends the session, never restarts it")
    void missingLoginTimeEndsTheSession() throws Exception {
        session.removeAttribute(SessionLifetimeFilter.AUTHENTICATED_AT);
        clock.advance(Duration.ofMinutes(1));
        assertThat(request(false)).isFalse();
    }

    @Test
    @DisplayName("fail closed: a missing activity time ends the session and does not re-record the login time")
    void missingActivityTimeEndsTheSession() throws Exception {
        session.removeAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT);
        clock.advance(ABSOLUTE.minusMinutes(1));
        assertThat(request(false)).isFalse();
    }

    @Test
    @DisplayName("fail closed: a timestamp that is not an Instant ends the session")
    void malformedTimestampEndsTheSession() throws Exception {
        session.setAttribute(SessionLifetimeFilter.AUTHENTICATED_AT, LOGIN.toString());
        assertThat(request(false)).isFalse();
    }

    @Test
    @DisplayName("an anonymous session (e.g. one holding only a CSRF token or a pending login) is left alone")
    void anonymousSessionUntouched() throws Exception {
        session = new MockHttpSession();
        session.setAttribute("pending", "authorization request");
        clock.advance(Duration.ofDays(1));
        assertThat(request(false)).isTrue();
        assertThat(session.getAttribute(SessionLifetimeFilter.LAST_ACTIVITY_AT)).isNull();
    }

    @Test
    @DisplayName("no session: nothing is created")
    void noSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(request.getSession(false)).isNull();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
