package pl.example.cvchecker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "cvchecker.persistence=memory")
class CvCheckerBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
