package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImportCommitLogRepository extends JpaRepository<ImportCommitLog, Long> {

    List<ImportCommitLog> findAllByOrderByCommittedAtDesc();

    List<ImportCommitLog> findByImportRef(String importRef);
}
