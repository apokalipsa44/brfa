package pl.example.cvchecker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CvCheckerBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CvCheckerBackendApplication.class, args);
	}

}
