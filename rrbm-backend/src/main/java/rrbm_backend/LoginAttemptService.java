package rrbm_backend;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * M-4.1: in-memory brute-force throttle for the login endpoint.
 *
 * After {@link #MAX_ATTEMPTS} consecutive failures for the same identifier, that
 * identifier is locked for {@link #LOCK_DURATION_MS}. A successful login clears the
 * counter. State is per-process and resets on restart — acceptable for the current
 * single-instance deployment (no DB migration / persistence required). If RRBM later
 * scales horizontally, this should move to a shared store (e.g. Redis).
 */
@Service
public class LoginAttemptService {

    static final int MAX_ATTEMPTS = 5;
    static final long LOCK_DURATION_MS = 15 * 60 * 1000L;   // 15 minutes locked out
    static final long ATTEMPT_WINDOW_MS = 15 * 60 * 1000L;  // failures older than this don't count

    private static final class Attempt {
        int count;
        long firstFailureAt;
        long lockedUntil;
    }

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    private String norm(String identifier) {
        return identifier == null ? "" : identifier.trim().toLowerCase();
    }

    /** True if the identifier is currently locked out. */
    public boolean isBlocked(String identifier) {
        return secondsUntilUnlock(identifier) > 0;
    }

    /** Seconds remaining on an active lockout, or 0 if not locked. */
    public long secondsUntilUnlock(String identifier) {
        Attempt a = attempts.get(norm(identifier));
        if (a == null) return 0;
        long remaining = a.lockedUntil - System.currentTimeMillis();
        return remaining > 0 ? (remaining + 999) / 1000 : 0;
    }

    /** Record a failed login; locks the identifier once MAX_ATTEMPTS is reached. */
    public void recordFailure(String identifier) {
        String key = norm(identifier);
        long now = System.currentTimeMillis();
        attempts.compute(key, (k, a) -> {
            if (a == null || now - a.firstFailureAt > ATTEMPT_WINDOW_MS || a.lockedUntil > 0 && now > a.lockedUntil) {
                a = new Attempt();
                a.firstFailureAt = now;
            }
            a.count++;
            if (a.count >= MAX_ATTEMPTS) {
                a.lockedUntil = now + LOCK_DURATION_MS;
            }
            return a;
        });
    }

    /** Clear all failure state for an identifier after a successful login. */
    public void recordSuccess(String identifier) {
        attempts.remove(norm(identifier));
    }
}
