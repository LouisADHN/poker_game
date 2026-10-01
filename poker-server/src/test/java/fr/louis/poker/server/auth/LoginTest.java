package fr.louis.poker.server.auth;

import com.jayway.jsonpath.JsonPath;
import fr.louis.poker.server.TestcontainersConfiguration;
import fr.louis.poker.server.security.TokenService;
import fr.louis.poker.server.user.UserAccount;
import fr.louis.poker.server.user.UserRepository;
import fr.louis.poker.server.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests de la connexion et de l'accès aux routes protégées par JWT.
 * Chaque test part d'un utilisateur "Louis" fraîchement inscrit, annulé à la fin du test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class LoginTest {

    private static final String PASSWORD = "motdepasse123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    private UserAccount louis;

    @BeforeEach
    void setUp() {
        louis = userService.register("Louis", PASSWORD);
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "%s", "password": "%s"}
                        """.formatted(username, password)));
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Connexion")
    class Login {

        @Test
        @DisplayName("Des identifiants valides renvoient un jeton, sa date d'expiration et le profil")
        void validCredentials() throws Exception {
            login("Louis", PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isNotEmpty())
                    .andExpect(jsonPath("$.expiresAt").exists())
                    .andExpect(jsonPath("$.user.id").value(louis.getId()))
                    .andExpect(jsonPath("$.user.username").value("Louis"));
        }

        @Test
        @DisplayName("Le pseudo est reconnu quelle que soit la casse")
        void usernameIsCaseInsensitive() throws Exception {
            login("LOUIS", PASSWORD).andExpect(status().isOk());
        }

        @Test
        @DisplayName("Un mauvais mot de passe renvoie 401")
        void wrongPassword() throws Exception {
            login("Louis", "mauvaismotdepasse")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.title").value("Identifiants invalides"));
        }

        @Test
        @DisplayName("Pseudo inconnu et mauvais mot de passe donnent exactement la même réponse")
        void unknownUserLooksLikeWrongPassword() throws Exception {
            String wrongPassword = login("Louis", "mauvaismotdepasse")
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            String unknownUser = login("Personne", "mauvaismotdepasse")
                    .andExpect(status().isUnauthorized())
                    .andReturn().getResponse().getContentAsString();

            // Seul le champ "instance" peut différer selon les versions ; on compare titre et détail
            assertEquals(JsonPath.<String>read(wrongPassword, "$.title"), JsonPath.<String>read(unknownUser, "$.title"));
            assertEquals(JsonPath.<String>read(wrongPassword, "$.detail"), JsonPath.<String>read(unknownUser, "$.detail"));
        }

        @Test
        @DisplayName("Des champs vides renvoient 400")
        void blankFields() throws Exception {
            login("", "")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.username").exists())
                    .andExpect(jsonPath("$.errors.password").exists());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Route protégée /api/users/me")
    class Me {

        @Test
        @DisplayName("Parcours complet : connexion, puis accès au profil avec le jeton reçu")
        void loginThenAccessProfile() throws Exception {
            String body = login("Louis", PASSWORD).andReturn().getResponse().getContentAsString();
            String token = JsonPath.read(body, "$.token");

            me(token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(louis.getId()))
                    .andExpect(jsonPath("$.username").value("Louis"));
        }

        @Test
        @DisplayName("Sans jeton : 401")
        void noToken() throws Exception {
            mockMvc.perform(get("/api/users/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Avec un jeton qui n'en est pas un : 401")
        void garbageToken() throws Exception {
            me("ceci.nest.pasunjeton").andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Un jeton falsifié pour usurper un autre compte est refusé")
        void forgedTokenIsRejected() throws Exception {
            String validToken = tokenService.issue(louis).value();
            String[] parts = validToken.split("\\.");

            // On remplace le contenu par celui d'un autre utilisateur, en gardant la signature d'origine
            String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    """
                    {"iss":"poker-server","sub":"999","username":"Pirate","exp":9999999999}
                    """.strip().getBytes(StandardCharsets.UTF_8));
            String forgedToken = parts[0] + "." + forgedPayload + "." + parts[2];

            me(forgedToken).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Un jeton expiré est refusé")
        void expiredTokenIsRejected() throws Exception {
            Instant past = Instant.now().minus(Duration.ofHours(2));
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(TokenService.ISSUER)
                    .issuedAt(past)
                    .expiresAt(past.plus(Duration.ofHours(1))) // expiré depuis une heure
                    .subject(String.valueOf(louis.getId()))
                    .build();
            JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
            String expiredToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

            me(expiredToken).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Jeton valide mais compte supprimé : 404")
        void deletedAccount() throws Exception {
            String token = tokenService.issue(louis).value();
            userRepository.delete(louis);
            userRepository.flush();

            me(token).andExpect(status().isNotFound());
        }
    }
}