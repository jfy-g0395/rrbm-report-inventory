package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    List<StockTransfer> findAllByOrderByCreatedAtDesc();

    List<StockTransfer> findByStatusOrderByCreatedAtDesc(String status);
}
