package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyReportRepository extends JpaRepository<DailyReport, Long> {
    Optional<DailyReport> findByReportDate(LocalDate reportDate);
    List<DailyReport> findByReportDateBetweenOrderByReportDateDesc(LocalDate start, LocalDate end);
}
