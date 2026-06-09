package rrbm_backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationEmailRepository extends JpaRepository<NotificationEmail, Long> {
    boolean existsByEmail(String email);
}
