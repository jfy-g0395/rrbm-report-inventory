package rrbm_backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for validating and managing master keys used for privileged operations.
 * Up to 3 active keys are supported simultaneously; any one of them validates.
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
     * Validates a raw master key against ALL active stored keys.
     * Returns true if ANY active key matches.
     */
    public boolean validateMasterKey(String rawKey) {
        List<MasterKey> activeKeys = masterKeyRepository.findByIsActiveTrue();
        if (activeKeys.isEmpty()) {
            // Fallback: check the latest key (for backward compat with pre-V32 data)
            MasterKey latest = masterKeyRepository.findTopByOrderByCreatedAtDesc();
            return latest != null && passwordEncoder.matches(rawKey, latest.getKeyHash());
        }
        return activeKeys.stream().anyMatch(k -> passwordEncoder.matches(rawKey, k.getKeyHash()));
    }

    /**
     * @deprecated Use addMasterKey instead. Kept for backward compatibility.
     */
    public MasterKey rotateMasterKey(String rawKey, Long adminUserId) {
        return addMasterKey(rawKey, "Default", adminUserId);
    }

    /** Add a new active master key. Max 3 active keys enforced. */
    public MasterKey addMasterKey(String rawKey, String label, Long adminUserId) {
        long activeCount = masterKeyRepository.countByIsActiveTrue();
        if (activeCount >= 3) {
            throw new RuntimeException("Maximum of 3 active master keys reached. Remove one before adding a new key.");
        }
        MasterKey mk = new MasterKey();
        mk.setKeyHash(passwordEncoder.encode(rawKey));
        mk.setLabel(label != null && !label.isBlank() ? label.trim() : "Key " + (activeCount + 1));
        mk.setActive(true);
        mk.setUpdatedBy(adminUserId);
        return masterKeyRepository.save(mk);
    }

    /** Deactivate a master key. At least 1 must remain active. */
    public void removeMasterKey(Long keyId) {
        long activeCount = masterKeyRepository.countByIsActiveTrue();
        if (activeCount <= 1) {
            throw new RuntimeException("Cannot remove the last active master key. At least one must remain.");
        }
        masterKeyRepository.findById(keyId).ifPresent(mk -> {
            mk.setActive(false);
            masterKeyRepository.save(mk);
        });
    }

    /** List all active master keys (without hashes — safe for frontend). */
    public List<MasterKey> listActiveKeys() {
        return masterKeyRepository.findByIsActiveTrue();
    }
}
