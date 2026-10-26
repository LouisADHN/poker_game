package fr.louis.poker.server.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests des jetons d'invité et des rôles qu'ils donnent.
 * Sans Spring : on fabrique soi-même l'encodeur et le décodeur, avec une clé tirée au hasard.
 */
class TokenServiceTest {

    private TokenService tokenService;
    private JwtDecoder decoder;

    @BeforeEach
    void setUp() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");

        tokenService = new TokenService(
                new NimbusJwtEncoder(new ImmutableSecret<>(key)),
                new JwtProperties(Base64.getEncoder().encodeToString(keyBytes), Duration.ofHours(12)));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Les RÔLES donnés à un jeton par le convertisseur de l'application.
     * Spring Security 7 ajoute aussi des autorités « facteur » (FACTOR_BEARER : authentifié par un jeton),
     * qui servent à l'authentification multifacteur : elles ne nous concernent pas ici.
     */
    private List<String> rolesOf(Jwt jwt) {
        return new JwtConfig().jwtAuthenticationConverter().convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .toList();
    }

    @Test
    @DisplayName("Un jeton d'invité porte son identifiant négatif, son pseudo et le claim guest")
    void guestToken() {
        long guestId = tokenService.newGuestId();

        Jwt jwt = decoder.decode(tokenService.issueGuest(guestId, "Mamie").value());

        assertEquals(String.valueOf(guestId), jwt.getSubject());
        assertEquals("Mamie", jwt.getClaimAsString(TokenService.USERNAME_CLAIM));
        assertTrue(TokenService.isGuest(jwt));
        assertEquals(List.of(TokenService.ROLE_GUEST), rolesOf(jwt));
    }

    @Test
    @DisplayName("Un jeton sans le claim guest (celui d'un compte, même ancien) donne le rôle PLAYER")
    void accountTokenIsPlayer() {
        // Un jeton fabriqué comme ceux d'avant les invités : aucun claim "guest"
        Jwt jwt = Jwt.withTokenValue("jeton")
                .header("alg", "HS256")
                .subject("42")
                .claim(TokenService.USERNAME_CLAIM, "Louis")
                .build();

        assertFalse(TokenService.isGuest(jwt));
        assertEquals(List.of(TokenService.ROLE_PLAYER), rolesOf(jwt));
    }

    @Test
    @DisplayName("Les identifiants d'invité sont négatifs et ne se répètent pas")
    void guestIdsAreNegativeAndUnique() {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            long id = tokenService.newGuestId();
            assertTrue(id < 0, "Identifiant positif : " + id);
            ids.add(id);
        }
        assertEquals(10_000, ids.size());
    }

    @Test
    @DisplayName("Un identifiant positif (celui d'un compte) est refusé pour un invité")
    void guestIdMustBeNegative() {
        assertThrows(IllegalArgumentException.class, () -> tokenService.issueGuest(7L, "Imposteur"));
    }
}