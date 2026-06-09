package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {
    List<DeliveryLog> findByReportDateOrderByCreatedAtDesc(LocalDate date);
    List<DeliveryLog> findByReportDateBetweenOrderByCreatedAtDesc(LocalDate start, LocalDate end);
    List<DeliveryLog> findAllByOrderByCreatedAtDesc();
    boolean existsByReceiptNumber(String receiptNumber);
}
