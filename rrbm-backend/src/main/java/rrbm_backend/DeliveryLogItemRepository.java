package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface DeliveryLogItemRepository extends JpaRepository<DeliveryLogItem, Long> {

    /** Find all items belonging to a delivery log by the log's ID. */
    @Query("SELECT i FROM DeliveryLogItem i WHERE i.deliveryLog.id = :deliveryLogId")
    List<DeliveryLogItem> findByDeliveryLogId(@Param("deliveryLogId") Long deliveryLogId);

    /** Find all items with rejectedQty > 0 within the given date range (inclusive), ordered by date desc. */
    @Query("SELECT i FROM DeliveryLogItem i JOIN FETCH i.deliveryLog dl " +
           "WHERE i.rejectedQty > 0 " +
           "  AND dl.reportDate >= :start " +
           "  AND dl.reportDate <= :end " +
           "ORDER BY dl.reportDate DESC, dl.id DESC")
    List<DeliveryLogItem> findRejectedByDateRange(@Param("start") java.time.LocalDate start,
                                                  @Param("end")   java.time.LocalDate end);
}
