package edu.campus.print.security;

import edu.campus.print.config.AgentProperties;
import edu.campus.print.config.CounterProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Guessing the counter password must not work, from one address or from thousands. */
class CounterGuardTest {

    private static final long MINUTE = 60_000L;

    @Test
    void oneAddressGetsTenTriesInTenMinutes() {
        CounterAuthFilter.Guard g = new CounterAuthFilter.Guard();
        long t = 1_000_000;
        for (int i = 0; i < 10; i++) {
            assertThat(g.paused("198.51.100.7", t)).isFalse();
            g.failed("198.51.100.7", t += 1000);
        }
        assertThat(g.paused("198.51.100.7", t)).isTrue();
        assertThat(g.paused("203.0.113.1", t)).isFalse();              // other people are not affected
        assertThat(g.paused("198.51.100.7", t + 11 * MINUTE)).isFalse();
    }

    @Test
    void aScriptThatChangesItsAddressEveryTryIsStoppedByTheTotal() {
        CounterAuthFilter.Guard g = new CounterAuthFilter.Guard();
        long t = 1_000_000;
        boolean started = false;
        for (int i = 0; i < CounterAuthFilter.MAX_FAILURES_IN_TOTAL; i++) {
            assertThat(g.paused("10.9." + (i / 250) + "." + (i % 250), t)).isFalse();
            started = g.failed("10.9." + (i / 250) + "." + (i % 250), t += 100);
        }
        assertThat(started).isTrue();
        // Now nobody signs in with the password for 5 minutes, from any address.
        assertThat(g.paused("203.0.113.200", t)).isTrue();
        assertThat(g.paused("203.0.113.200", t + 4 * MINUTE)).isTrue();
        assertThat(g.paused("203.0.113.200", t + 5 * MINUTE + 1)).isFalse();
    }

    @Test
    void whileTheGuessingGoesOnThePauseGrows() {
        CounterAuthFilter.Guard g = new CounterAuthFilter.Guard();
        long t = 1_000_000;
        long[] pauses = new long[4];
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < CounterAuthFilter.MAX_FAILURES_IN_TOTAL; i++) g.failed("a" + round + "-" + i, t += 10);
            long end = t;
            while (g.paused("someone", end)) end += MINUTE;
            pauses[round] = end - t;
            t = end;
        }
        assertThat(pauses[0]).isBetween(5 * MINUTE, 6 * MINUTE);
        assertThat(pauses[1]).isBetween(10 * MINUTE, 11 * MINUTE);
        assertThat(pauses[2]).isBetween(20 * MINUTE, 21 * MINUTE);
        assertThat(pauses[3]).isBetween(40 * MINUTE, 41 * MINUTE);
        // Half an hour of quiet: it starts small again.
        t += 31 * MINUTE;
        for (int i = 0; i < CounterAuthFilter.MAX_FAILURES_IN_TOTAL; i++) g.failed("later-" + i, t += 10);
        assertThat(g.paused("someone", t + 4 * MINUTE)).isTrue();
        assertThat(g.paused("someone", t + 5 * MINUTE + 1)).isFalse();
    }

    @Test
    void aSignInTokenIsTiedToThePassword() {
        AgentProperties agent = new AgentProperties("a-test-token-secret-that-is-long-enough-123", 900, 300, 60);
        CounterSessions sessions = new CounterSessions(agent, new CounterProperties("counter-password"));
        String token = sessions.issue();
        assertThat(sessions.valid(token)).isTrue();
        assertThat(sessions.valid(token + "x")).isFalse();
        assertThat(sessions.valid("x" + token)).isFalse();
        assertThat(sessions.valid("")).isFalse();
        assertThat(sessions.valid("no dots")).isFalse();
        assertThat(sessions.valid(null)).isFalse();
        // An old date with a signature of today's: the signature covers the date.
        assertThat(sessions.valid("1000000000" + token.substring(token.indexOf('.')))).isFalse();
        // The password was changed: every screen is signed out.
        assertThat(new CounterSessions(agent, new CounterProperties("another-password")).valid(token)).isFalse();
        assertThat(new CounterSessions(agent, new CounterProperties("counter-password")).valid(token)).isTrue();
    }
}
