package co.edu.icesi.student360.gateway.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/** Replaces the JWKS-backed decoder with one bound to a key pair generated for the test JVM. */
@TestConfiguration
public class TestJwtConfiguration {

  @Bean
  public KeyPair testKeyPair() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    return generator.generateKeyPair();
  }

  @Bean
  @Primary
  public ReactiveJwtDecoder testJwtDecoder(KeyPair keyPair) {
    return NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
  }

  @Bean
  public TestTokens testTokens(KeyPair keyPair) {
    return new TestTokens(keyPair);
  }
}
