package rrbm_backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Service for validating and managing the master key used for privileged operations.
 * The key is stored as a BCrypt hash in the {@code master_keys} table.
 */
@Service
public class MasterKeyService {

    private final MasterKeyRepository masterKeyRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    @Autowired
    public MasterKeyService(MasterKeyRepository masterKeyRepository) {
        this.masterKeyRepository = masterKeyRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * Validates a raw master key against the stored BCrypt hash.
     *
     * @param rawKey the plain‑text master key supplied by the user
     * @return {@code true} if the key matches the stored hash, {@code false} otherwise
     */
    public boolean validateMasterKey(String rawKey) {
        MasterKey stored = masterKeyRepository.findTopByOrderByCreatedAtDesc();
        if (stored == null) {
            // No master key configured – reject any attempt
            return false;
        }
        return passwordEncoder.matches(rawKey, stored.getKeyHash());
    }

    /**
     * Creates or rotates the master key. Only callers with proper admin privileges should invoke this.
     *
     * @param rawKey the new plain‑text master key
     * @param adminUserId the id of the admin performing the rotation
     */
    public MasterKey rotateMasterKey(String rawKey, Long adminUserId) {
        String hash = passwordEncoder.encode(rawKey);
        MasterKey mk = new MasterKey();
        mk.setKeyHash(hash);
        mk.setUpdatedBy(adminUserId);
        return masterKeyRepository.save(mk);
    }
}
