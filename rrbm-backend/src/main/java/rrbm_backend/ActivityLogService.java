package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;

@Service
public class ActivityLogService {

    private final ActivityLogRepository repo;

    public ActivityLogService(ActivityLogRepository repo) {
        this.repo = repo;
    }

    public void log(Long userId, String userName, String action, String description,
                    String entityType, String entityId) {
        ActivityLog entry = new ActivityLog();
        entry.setUserId(userId);
        entry.setUserName(userName);
        entry.setAction(action);
        entry.setDescription(description);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        repo.save(entry);
    }

    public List<ActivityLog> getTodayLogs() {
        return repo.findByReportDateOrderByCreatedAtDesc(LocalDate.now());
    }

    public List<ActivityLog> getLogsByDate(LocalDate date) {
        return repo.findByReportDateOrderByCreatedAtDesc(date);
    }

    public List<ActivityLog> getLogsBetween(LocalDate start, LocalDate end) {
        return repo.findByReportDateBetweenOrderByCreatedAtDesc(start, end);
    }

    @Transactional
    public int closeLogsForDate(LocalDate date) {
        return repo.closeLogsForDate(date);
    }
}
