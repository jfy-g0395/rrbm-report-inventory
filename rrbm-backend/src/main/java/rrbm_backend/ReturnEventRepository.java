package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ReturnEventRepository extends JpaRepository<ReturnEvent, Long> {

    List<ReturnEvent> findByOrderIdOrderByCreatedAtDesc(String orderId);

    /** Backs the "To Refund" tab — events with an unpaid refund still owed. */
    List<ReturnEvent> findByRefundStatusOrderByCreatedAtDesc(String refundStatus);

    /** Eager-fetch the line items (for history display and detail views). */
    @Query("select distinct e from ReturnEvent e left join fetch e.items "
         + "where e.orderId = ?1 order by e.createdAt desc")
    List<ReturnEvent> findWithItemsByOrderId(String orderId);
}
