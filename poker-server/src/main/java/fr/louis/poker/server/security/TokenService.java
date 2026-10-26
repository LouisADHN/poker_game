package fr.louis.poker.server.security;

import fr.louis.poker.server.user.UserAccount;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Crée les jetons JWT : ceux des comptes (à la connexion) et ceux des invités (mode local).
 *
 * Un invité n'existe pas en base. Son jeton porte :
 * - un identifiant NÉGATIF (les comptes, eux, ont des identifiants positifs donnés par la base) ;
 * - le claim "guest": true, qui lui donne le rôle GUEST au lieu de PLAYER (voir JwtConfig).
 */
@Service
public class TokenService {

    public static final String ISSUER = "poker-server";
    public static final String USERNAME_CLAIM = "username";
    /** Présent (et vrai) uniquement dans le jeton d'un invité. */
    public static final String GUEST_CLAIM = "guest";

    /** Rôles Spring Security : un compte, ou un invité aux droits limités. */
    public static final String ROLE_PLAYER = "ROLE_PLAYER";
    public static final String ROLE_GUEST = "ROLE_GUEST";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    /** Un jeton signé et sa date d'expiration. */
    public record IssuedToken(String value, Instant expiresAt) {
    }

    /** Le jeton d'un compte, remis à la connexion. */
    public IssuedToken issue(UserAccount user) {
        return encode(user.getId(), user.getUsername(), false);
    }

    /**
     * Le jeton d'un invité. Aucun compte n'est créé : l'invité n'existe que dans ce jeton.
     *
     * @param guestId identifiant négatif, voir newGuestId()
     */
    public IssuedToken issueGuest(long guestId, String username) {
        if (guestId >= 0) {
            throw new IllegalArgumentException("L'identifiant d'un invité doit être négatif");
        }
        return encode(guestId, username, true);
    }

    /**
     * Un nouvel identifiant d'invité : négatif (jamais celui d'un compte) et tiré au hasard.
     *
     * Pourquoi pas un simple compteur -1, -2, -3… ? Il repartirait de -1 à chaque redémarrage
     * du serveur, alors que les jetons déjà distribués restent valables : deux invités
     * auraient le même identifiant. Avec un tirage sur 63 bits, une collision est impossible en pratique.
     */
    public long newGuestId() {
        return -random.nextLong(1, Long.MAX_VALUE);
    }

    /** Vrai si ce jeton (déjà vérifié) est celui d'un invité. Un jeton sans le claim est celui d'un compte. */
    public static boolean isGuest(Jwt jwt) {
        return Boolean.TRUE.equals(jwt.getClaimAsBoolean(GUEST_CLAIM));
    }

    private IssuedToken encode(long userId, String username, boolean guest) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.expiration());

        // Contenu du jeton : LISIBLE par tous, donc aucune information secrète
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(String.valueOf(userId))     // l'identifiant, qui ne change jamais
                .claim(USERNAME_CLAIM, username);    // pratique pour l'affichage côté client
        if (guest) {
            builder.claim(GUEST_CLAIM, true);
        }
        JwtClaimsSet claims = builder.build();

        // L'algorithme doit être précisé explicitement : HMAC avec SHA-256
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }
}