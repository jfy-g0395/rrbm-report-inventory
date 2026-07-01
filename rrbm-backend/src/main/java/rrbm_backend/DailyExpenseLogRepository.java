package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyExpenseLogRepository extends JpaRepository<DailyExpenseLog, Long> {

    Optional<DailyExpenseLog> findByReportDate(LocalDate reportDate);

    /** Newest day first — used by the Expense Log browse tab. */
    List<DailyExpenseLog> findAllByOrderByReportDateDesc();
}
