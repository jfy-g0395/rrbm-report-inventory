package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResellerProductPriceRepository extends JpaRepository<ResellerProductPrice, Long> {

    List<ResellerProductPrice> findByResellerId(Long resellerId);

    void deleteByResellerId(Long resellerId);
}
