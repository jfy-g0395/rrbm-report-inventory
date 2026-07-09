package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    List<Employee> findByStatusOrderByLastNameAscFirstNameAsc(String status);

    List<Employee> findAllByOrderByLastNameAscFirstNameAsc();

    // Max 4-digit sequence for a code prefix (e.g. 'EMP-2026-%'). SUBSTRING position 10
    // extracts NNNN from "EMP-YYYY-NNNN" (positions 1-9 = "EMP-YYYY-").
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(employee_code, 10) AS INTEGER)), 0) " +
                   "FROM employees WHERE employee_code LIKE :prefix",
           nativeQuery = true)
    int maxSequenceForPrefix(@Param("prefix") String prefix);
}
