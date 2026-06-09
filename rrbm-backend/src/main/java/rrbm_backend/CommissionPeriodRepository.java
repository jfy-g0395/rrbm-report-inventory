package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface CommissionPeriodRepository extends JpaRepository<CommissionPeriod, Long> {

    List<CommissionPeriod> findByStatusOrderByStartDateDesc(String status);

    // Returns periods where start_date <= d1 AND end_date >= d2.
    // Overlap check: call with (proposedEndDate, proposedStartDate).
    // Coverage check: call with (orderDate, orderDate).
    List<CommissionPeriod> findByStartDateLessThanEqualAndEndDateGreaterThanEqual(
            LocalDate d1, LocalDate d2);

    // Count periods whose code starts with the given YYYY-MM- prefix (for code generation).
    long countByPeriodCodeStartingWith(String prefix);
}
