package rrbm_backend;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderIdCounterRepository extends JpaRepository<OrderIdCounter, String> {
    
    // Find counter with pessimistic write lock (prevents concurrent access)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM OrderIdCounter c WHERE c.dateKey = :dateKey")
    Optional<OrderIdCounter> findByDateKeyWithLock(String dateKey);
}