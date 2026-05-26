package gov.irs.directfile.planservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import gov.irs.directfile.models.autoconfigure.EncryptionAutoConfiguration;
import gov.irs.directfile.planservice.config.PlanServiceProperties;

@SpringBootApplication(exclude = EncryptionAutoConfiguration.class)
@EnableConfigurationProperties(PlanServiceProperties.class)
public class PlanServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlanServiceApplication.class, args);
    }
}
