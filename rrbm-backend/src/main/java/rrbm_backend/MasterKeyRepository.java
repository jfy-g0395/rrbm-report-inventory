package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MasterKeyRepository extends JpaRepository<MasterKey, Long> {
    MasterKey findTopByOrderByCreatedAtDesc();
    List<MasterKey> findByIsActiveTrue();
    long countByIsActiveTrue();
}
