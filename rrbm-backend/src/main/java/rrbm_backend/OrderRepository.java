package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, String> {
    
    // Find orders created today
    @Query("SELECT o FROM Order o WHERE CAST(o.createdAt AS date) = :date ORDER BY o.createdAt DESC")
    List<Order> findByCreatedAtDate(LocalDate date);
    
    // Find orders by status
    List<Order> findByStatusOrderByCreatedAtDesc(String status);
    
    // Find orders by customer name (search)
    List<Order> findByCustomerNameContainingIgnoreCaseOrderByCreatedAtDesc(String customerName);
}