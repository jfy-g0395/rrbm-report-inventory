package rrbm_backend;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PoYearCounterRepository extends JpaRepository<PoYearCounter, Integer> {

    /** Acquire a row-level write lock on the year counter row before reading.
     *  Concurrent calls block here until the current transaction commits,
     *  preventing duplicate PO numbers on simultaneous creates. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM PoYearCounter c WHERE c.year = :year")
    Optional<PoYearCounter> findByYearWithLock(@Param("year") Integer year);
}
