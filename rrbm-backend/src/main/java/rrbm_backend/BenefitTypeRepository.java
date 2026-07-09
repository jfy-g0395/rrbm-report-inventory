package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BenefitTypeRepository extends JpaRepository<BenefitType, Long> {
    List<BenefitType> findByActiveTrueOrderByName();
    Optional<BenefitType> findByNameIgnoreCase(String name);
}
