package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeEventRepository extends JpaRepository<EmployeeEvent, Long> {
    List<EmployeeEvent> findByEmployeeIdOrderByEventDateDescIdDesc(Long employeeId);
}
