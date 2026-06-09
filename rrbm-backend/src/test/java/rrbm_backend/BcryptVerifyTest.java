package rrbm_backend;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BcryptVerifyTest {
    @Test
    void generateCorrectHash() {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        String hash = enc.encode("ChangeMe123!");
        System.out.println("NEW HASH: " + hash);
        System.out.println("Verifies: " + enc.matches("ChangeMe123!", hash));
        assertTrue(enc.matches("ChangeMe123!", hash));
    }
}
