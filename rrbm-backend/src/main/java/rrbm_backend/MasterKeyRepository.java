package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MasterKeyRepository extends JpaRepository<MasterKey, Long> {
    // We only ever have a single active master key, but a method to fetch the latest can be useful
    MasterKey findTopByOrderByCreatedAtDesc();
}
