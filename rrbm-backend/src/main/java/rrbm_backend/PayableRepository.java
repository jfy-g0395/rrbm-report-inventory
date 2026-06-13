package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface PayableRepository extends JpaRepository<Payable, Long> {

    List<Payable> findAllByOrderByCreatedAtDesc();

    List<Payable> findByDeliveryLogId(Long deliveryLogId);

    List<Payable> findByStatus(String status);

    List<Payable> findByReceiptNumber(String receiptNumber);

    @Query("SELECT COALESCE(SUM(p.totalAmount - p.amountPaid), 0) FROM Payable p WHERE p.status <> 'PAID'")
    BigDecimal getTotalOutstanding();
}
