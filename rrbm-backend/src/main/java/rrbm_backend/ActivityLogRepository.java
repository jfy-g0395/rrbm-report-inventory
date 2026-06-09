package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDate;
import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {
    List<ActivityLog> findByReportDateOrderByCreatedAtDesc(LocalDate date);
    List<ActivityLog> findByReportDateBetweenOrderByCreatedAtDesc(LocalDate start, LocalDate end);
    List<ActivityLog> findByUserIdAndReportDateOrderByCreatedAtDesc(Long userId, LocalDate date);

    @Modifying
    @Query("UPDATE ActivityLog a SET a.isClosed = true WHERE a.reportDate = :date AND a.isClosed = false")
    int closeLogsForDate(LocalDate date);
}
