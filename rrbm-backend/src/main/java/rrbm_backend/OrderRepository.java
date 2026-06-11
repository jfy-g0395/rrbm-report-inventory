package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    
    // Find orders created today
    @Query("SELECT o FROM Order o WHERE CAST(o.createdAt AS date) = :date ORDER BY o.createdAt DESC")
    List<Order> findByCreatedAtDate(LocalDate date);
    
    // Find orders by status
    List<Order> findByStatusOrderByCreatedAtDesc(String status);
    
    // Find orders by customer name (search)
    List<Order> findByCustomerNameContainingIgnoreCaseOrderByCreatedAtDesc(String customerName);

    List<Order> findByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    // Date-range query for Order History view (#8)
    @Query("SELECT o FROM Order o WHERE CAST(o.createdAt AS date) BETWEEN :start AND :end ORDER BY o.createdAt DESC")
    List<Order> findByDateRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    // Date-range query with items eagerly fetched (for reports — avoids N+1 lazy load)
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE CAST(o.createdAt AS date) BETWEEN :start AND :end ORDER BY o.createdAt DESC")
    List<Order> findByDateRangeWithItems(@Param("start") LocalDate start, @Param("end") LocalDate end);

    // Today's orders with items eagerly fetched (for top-products-today)
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE CAST(o.createdAt AS date) = :date")
    List<Order> findByCreatedAtDateWithItems(@Param("date") LocalDate date);

    // Single order with items eagerly fetched (for inventory restore on refund/void)
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") String id);

    // Pessimistic write lock — used by collectOrder to prevent concurrent double-collect.
    // Must be called inside a @Transactional method; the lock is released at commit.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") String id);

    // Pessimistic write lock WITH items eagerly fetched — used by collectOrder when
    // commission entries need to be created (requires items to be available without
    // triggering a lazy load in a potentially detached state).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdForUpdateWithItems(@Param("id") String id);

    // CSV duplicate check: does an order with this external order ref already exist in notes?
    boolean existsByNotesContaining(String text);

    // Import duplicate check: has this import_ref (CSV receipt#) already been committed?
    boolean existsByImportRef(String importRef);

    // U3: Import history — all imported orders in a date range with creator eagerly fetched.
    @Query("SELECT o FROM Order o JOIN FETCH o.createdBy " +
           "WHERE o.imported = true AND CAST(o.createdAt AS date) BETWEEN :start AND :end " +
           "ORDER BY o.createdAt DESC")
    List<Order> findImportedOrdersWithCreatorByDateRange(@Param("start") LocalDate start,
                                                         @Param("end") LocalDate end);

    // U3: Per-receipt import detail — find by import_ref with items eagerly fetched.
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.importRef = :importRef")
    Optional<Order> findByImportRefWithItems(@Param("importRef") String importRef);

    // Orders for a specific agent, newest first, with items eagerly fetched.
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items WHERE o.agentId = :agentId ORDER BY o.createdAt DESC")
    List<Order> findByAgentIdWithItems(@Param("agentId") Long agentId);

    // All orders needing payment collection: only PENDING_COLLECTION status.
    // PENDING (on-hold) orders are excluded — they are visible in the Order List
    // and will resume to ACTIVE via the normal hold-resume flow. Mixing them into
    // Collections causes confusion and risks double-SALE on collect (M-7).
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items " +
           "WHERE o.status = 'PENDING_COLLECTION' " +
           "ORDER BY o.createdAt ASC")
    List<Order> findPendingCollections();

    // Find orders for an agent in a date range that don't have commission entries yet.
    // Used by backfill when a new period is opened.
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items " +
           "WHERE o.agentId = :agentId " +
           "AND CAST(o.createdAt AS date) BETWEEN :start AND :end " +
           "AND o.id NOT IN (SELECT e.orderId FROM CommissionEntry e WHERE e.agentId = :agentId) " +
           "ORDER BY o.createdAt ASC")
    List<Order> findOrdersWithoutCommissionEntries(
        @Param("agentId") Long agentId,
        @Param("start") LocalDate start,
        @Param("end") LocalDate end);

    // Find distinct agent IDs that have orders in a date range.
    // Used by backfill to know which agents to process.
    @Query("SELECT DISTINCT o.agentId FROM Order o " +
           "WHERE o.agentId IS NOT NULL " +
           "AND CAST(o.createdAt AS date) BETWEEN :start AND :end")
    List<Long> findAgentIdsWithOrdersInRange(
        @Param("start") LocalDate start,
        @Param("end") LocalDate end);
}