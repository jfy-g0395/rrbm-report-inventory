package rrbm_backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Simple service to record user actions in the {@code activity_log} table.
 * It is intentionally lightweight – callers just provide the user id, an action name,
 * and optional free‑form details. The service creates and persists an {@link ActivityLog} entity.
 */
@Service
public class ActivityLogService {

    private final ActivityLogRepository repository;

    @Autowired
    public ActivityLogService(ActivityLogRepository repository) {
        this.repository = repository;
    }

    /**
     * Record an action performed by a user.
     *
     * @param userId  the id of the user performing the action
     * @param action  a short identifier such as "login", "order_create", "product_add"
     * @param details optional free‑form details (may be {@code null})
     */
    public void log(Long userId, String action, String details) {
        ActivityLog entry = new ActivityLog();
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setDetails(details);
        repository.save(entry);
    }
}
