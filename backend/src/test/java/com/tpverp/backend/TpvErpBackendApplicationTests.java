package com.tpverp.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(properties = "tpv.installation.key-directory=target/test-installation-keys")
class TpvErpBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
