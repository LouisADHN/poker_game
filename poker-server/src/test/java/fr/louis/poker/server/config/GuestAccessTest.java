package fr.louis.poker.server.config;

import fr.louis.poker.server.TestcontainersConfiguration;
import fr.louis.poker.server.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ce qu'un invité (mode local) a le droit de faire sur l'API, et surtout ce qu'il n'a pas le droit de faire.
 * 403 = authentifié, mais pas autorisé ; tout autre code prouve que la sécurité l'a laissé passer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GuestAccessTest {

    /** Une table qui n'existe pas : la réponse prouve qu'on a franchi la sécurité, sans rien créer. */
    private static final long NO_TABLE = 987_654L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    private String guest;
    private String account;

    @BeforeEach
    void setUp() {
        guest = "Bearer " + tokenService.issueGuest(tokenService.newGuestId(), "Mamie").value();
        account = "Bearer " + accountToken();
    }

    /** Un jeton de compte, fabriqué comme dans LobbyControllerTest : sans claim "guest". */
    private String accountToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(TokenService.ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .subject("1")
                .claim(TokenService.USERNAME_CLAIM, "Louis")
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    // ------------------------------------------------------------------
    // Interdit à un invité

    @Test
    @DisplayName("Un invité ne voit ni la liste des tables, ni le classement, ni un profil")
    void guestCannotBrowse() throws Exception {
        mockMvc.perform(get("/api/tables").header(HttpHeaders.AUTHORIZATION, guest))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/leaderboard").header(HttpHeaders.AUTHORIZATION, guest))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, guest))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Un invité ne peut ni créer, ni rejoindre, ni lancer une table du lobby")
    void guestCannotUseTheLobby() throws Exception {
        mockMvc.perform(post("/api/tables").header(HttpHeaders.AUTHORIZATION, guest)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Table", "smallBlind": 10, "bigBlind": 20,
                                 "startingChips": 1000, "maxPlayers": 2}
                                """))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tables/" + NO_TABLE + "/join").header(HttpHeaders.AUTHORIZATION, guest))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tables/" + NO_TABLE + "/start").header(HttpHeaders.AUTHORIZATION, guest))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Permis à un invité

    @Test
    @DisplayName("Un invité peut consulter une table et quitter sa partie")
    void guestCanPlay() throws Exception {
        // 404 et 409 et pas 403 : la sécurité l'a laissé passer, c'est la table qui n'existe pas
        mockMvc.perform(get("/api/tables/" + NO_TABLE).header(HttpHeaders.AUTHORIZATION, guest))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tables/" + NO_TABLE + "/leave-game").header(HttpHeaders.AUTHORIZATION, guest))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------
    // Les comptes ne perdent rien

    @Test
    @DisplayName("Un compte (jeton sans claim guest) garde tous ses droits")
    void accountKeepsItsRights() throws Exception {
        mockMvc.perform(get("/api/tables").header(HttpHeaders.AUTHORIZATION, account))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/leaderboard").header(HttpHeaders.AUTHORIZATION, account))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tables/" + NO_TABLE).header(HttpHeaders.AUTHORIZATION, account))
                .andExpect(status().isNotFound());
    }
}