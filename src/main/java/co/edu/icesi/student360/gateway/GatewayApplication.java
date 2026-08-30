package co.edu.icesi.student360.gateway;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {

  public static void main(String[] args) {
    // .env is optional and never shadows a real environment: a variable already present as an
    // environment variable or system property wins over the file. Locally the file declares
    // SPRING_PROFILES_ACTIVE=dev; on Cloud Run there is no file and Terraform's env vars rule.
    Dotenv.configure().ignoreIfMissing().load().entries().stream()
        .filter(entry -> System.getenv(entry.getKey()) == null)
        .filter(entry -> System.getProperty(entry.getKey()) == null)
        .forEach(entry -> System.setProperty(entry.getKey(), entry.getValue()));
    SpringApplication.run(GatewayApplication.class, args);
  }
}
