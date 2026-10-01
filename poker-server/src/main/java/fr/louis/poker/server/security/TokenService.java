package fr.louis.poker.server.security;

import fr.louis.poker.server.user.UserAccount;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Crée les jetons JWT remis aux joueurs lors de la connexion.
 */
@Service
public class TokenService {

    public static final String ISSUER = "poker-server";
    public static final String USERNAME_CLAIM = "username";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public TokenService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /** Un jeton signé et sa date d'expiration. */
    public record IssuedToken(String value, Instant expiresAt) {
    }

    public IssuedToken issue(UserAccount user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.expiration());

        // Contenu du jeton : LISIBLE par tous, donc aucune information secrète
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(String.valueOf(user.getId()))    // l'identifiant, qui ne change jamais
                .claim(USERNAME_CLAIM, user.getUsername()) // pratique pour l'affichage côté client
                .build();

        // L'algorithme doit être précisé explicitement : HMAC avec SHA-256
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}