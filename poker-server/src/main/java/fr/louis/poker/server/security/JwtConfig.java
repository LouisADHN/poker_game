package fr.louis.poker.server.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/**
 * Crée les objets qui signent (encoder) et vérifient (decoder) les jetons JWT.
 * Algorithme HS256 : une seule clé secrète, connue uniquement du serveur, sert aux deux.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final int MIN_KEY_BYTES = 32; // 256 bits, minimum pour HS256

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException("La propriété poker.jwt.secret est obligatoire");
        }
        byte[] keyBytes = Base64.getDecoder().decode(properties.secret());
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("La clé JWT doit faire au moins 256 bits (32 octets), reçu : "
                    + keyBytes.length + " octets");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    /**
     * Utilisé automatiquement par Spring Security pour vérifier le jeton de chaque requête :
     * signature valide, et date d'expiration non dépassée.
     */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}