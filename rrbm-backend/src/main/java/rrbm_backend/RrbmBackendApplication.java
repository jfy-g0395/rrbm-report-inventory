package rrbm_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class RrbmBackendApplication {

	private static final Logger log = LoggerFactory.getLogger(RrbmBackendApplication.class);

	@Autowired(required = false)
	private MasterKeyRepository masterKeyRepository;

	@Autowired(required = false)
	private MasterKeyService masterKeyService;

	@Value("${rrbm.initial.master.key}")
	private String initialMasterKey;

	public static void main(String[] args) {
		SpringApplication.run(RrbmBackendApplication.class, args);
	}

	// Seeds the initial master key on first boot if none exists.
	// Key is read from RRBM_INITIAL_MASTER_KEY env var — never hardcoded.
	@Bean
	ApplicationRunner seedMasterKey() {
		return args -> {
			if (masterKeyRepository == null || masterKeyService == null) {
				log.warn("MasterKeyRepository or MasterKeyService not available — skipping seed. "
					+ "Ensure the master_keys table exists (Flyway V8/V32).");
				return;
			}
			if (masterKeyRepository.count() == 0) {
				log.info("Seeding initial master key...");
				masterKeyService.rotateMasterKey(initialMasterKey, null);
				log.info("Master key seeded successfully.");
			}
		};
	}
}

