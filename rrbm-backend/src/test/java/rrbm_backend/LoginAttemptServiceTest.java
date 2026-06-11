package rrbm_backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M-4.1: in-memory login lockout logic (deterministic, no Spring context / DB).
 */
class LoginAttemptServiceTest {

    @Test
    void notBlockedBelowThreshold() {
        LoginAttemptService svc = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS - 1; i++) {
            svc.recordFailure("user@x.com");
        }
        assertFalse(svc.isBlocked("user@x.com"));
        assertEquals(0, svc.secondsUntilUnlock("user@x.com"));
    }

    @Test
    void blockedAtThreshold() {
        LoginAttemptService svc = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            svc.recordFailure("user@x.com");
        }
        assertTrue(svc.isBlocked("user@x.com"));
        assertTrue(svc.secondsUntilUnlock("user@x.com") > 0);
    }

    @Test
    void successClearsCounter() {
        LoginAttemptService svc = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            svc.recordFailure("user@x.com");
        }
        assertTrue(svc.isBlocked("user@x.com"));
        svc.recordSuccess("user@x.com");
        assertFalse(svc.isBlocked("user@x.com"));
    }

    @Test
    void identifierIsCaseAndWhitespaceInsensitive() {
        LoginAttemptService svc = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            svc.recordFailure("User@X.com");
        }
        // same identity in a different case / with padding must be treated as locked
        assertTrue(svc.isBlocked("  user@x.com  "));
    }

    @Test
    void separateIdentifiersAreIndependent() {
        LoginAttemptService svc = new LoginAttemptService();
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            svc.recordFailure("victim@x.com");
        }
        assertTrue(svc.isBlocked("victim@x.com"));
        assertFalse(svc.isBlocked("bystander@x.com"));
    }
}
