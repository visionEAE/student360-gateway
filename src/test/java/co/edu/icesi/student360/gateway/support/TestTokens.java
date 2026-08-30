package co.edu.icesi.student360.gateway.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Mints access tokens shaped exactly like the SSO's, signed with the test key pair. */
public class TestTokens {

  private final RSASSASigner signer;

  TestTokens(KeyPair keyPair) {
    this.signer = new RSASSASigner(keyPair.getPrivate());
  }

  public String forUser(UUID userId, List<String> roles, String externalReference) {
    Instant now = Instant.now();
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("http://localhost:8081")
            .subject(userId.toString())
            .audience("student360-api")
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(900)))
            .jwtID(UUID.randomUUID().toString())
            .claim("roles", roles)
            .claim("ref", externalReference)
            .claim("sid", UUID.randomUUID().toString())
            .build();
    SignedJWT jwt =
        new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException exception) {
      throw new IllegalStateException(exception);
    }
    return jwt.serialize();
  }
}
