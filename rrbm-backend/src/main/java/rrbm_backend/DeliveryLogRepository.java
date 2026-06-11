package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long> {
    List<DeliveryLog> findByReportDateOrderByCreatedAtDesc(LocalDate date);
    List<DeliveryLog> findByReportDateBetweenOrderByCreatedAtDesc(LocalDate start, LocalDate end);
    List<DeliveryLog> findAllByOrderByCreatedAtDesc();
    boolean existsByReceiptNumber(String receiptNumber);
    Optional<DeliveryLog> findByReceiptNumber(String receiptNumber);
    List<DeliveryLog> findByCreatedAtBetween(OffsetDateTime start, OffsetDateTime end);
}
