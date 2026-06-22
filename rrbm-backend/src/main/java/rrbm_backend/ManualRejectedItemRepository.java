package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ManualRejectedItemRepository extends JpaRepository<ManualRejectedItem, Long> {
    List<ManualRejectedItem> findByReportDateBetweenOrderByReportDateDesc(LocalDate start, LocalDate end);
}
