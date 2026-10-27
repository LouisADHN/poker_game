package fr.louis.poker.server.lobby;

import com.jayway.jsonpath.JsonPath;
import fr.louis.poker.server.TestcontainersConfiguration;
import fr.louis.poker.server.game.GameService;
import fr.louis.poker.server.security.TokenService;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests des routes du mode local, de bout en bout : vraie sécurité, vrais jetons, vrai lobby.
 *
 * Le scénario visé : Louis crée la table sur la télé (il est l'hôte, pas un joueur),
 * Mamie la rejoint en invitée avec le code, Alice avec son compte, puis Louis lance la partie.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LocalControllerTest {

    private static final String LOCAL_TABLE = """
            {"name": "Soirée poker", "smallBlind": 10, "bigBlind": 20,
             "startingChips": 1000, "maxPlayers": 3}
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtEncoder jwtEncoder;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private LobbyService lobbyService;
    @Autowired
    private GameService gameService;

    /** Louis, l'hôte : le compte connecté sur la télé. */
    private String host;
    /** Alice, qui joue avec son compte depuis son téléphone. */
    private String alice;

    /** La table locale créée par le test (0 si aucune). */
    private long tableId;

    @BeforeEach
    void setUp() {
        lobbyService.clear();
        host = accountToken(1L, "Louis");
        alice = accountToken(2L, "Alice");
    }

    @AfterEach
    void tearDown() throws Exception {
        // Une partie lancée tourne dans ses propres threads : on l'arrête et on attend sa fin,
        // sinon elle pourrait fermer la table n°1 du test suivant (le lobby renumérote à partir de 1)
        gameService.stopAll();
        long deadline = System.currentTimeMillis() + 5000;
        while (gameService.isRunning(tableId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        lobbyService.clear();
    }

    /** Un jeton de compte, fabriqué directement comme dans LobbyControllerTest. */
    private String accountToken(long userId, String username) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(TokenService.ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .subject(String.valueOf(userId))
                .claim(TokenService.USERNAME_CLAIM, username)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private ResultActions createLocal(String token) throws Exception {
        return mockMvc.perform(post("/api/local")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOCAL_TABLE));
    }

    /** L'hôte crée la table locale. Retient son identifiant et renvoie son code d'accès. */
    private String createLocalTable() throws Exception {
        String json = createLocal(host)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        tableId = ((Number) JsonPath.read(json, "$.table.id")).longValue();
        return JsonPath.read(json, "$.joinCode");
    }

    /** Un invité s'assoit avec ce pseudo. Pas d'en-tête Authorization : il n'a pas encore de jeton. */
    private ResultActions joinAsGuest(String code, String username) throws Exception {
        return mockMvc.perform(post("/api/local/" + code + "/guest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"username": "%s"}
                        """.formatted(username)));
    }

    /** Un invité s'assoit, et renvoie son jeton. */
    private String guestToken(String code, String username) throws Exception {
        String json = joinAsGuest(code, username)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(json, "$.token");
    }

    private ResultActions joinWithCode(String token, String code) throws Exception {
        return mockMvc.perform(post("/api/local/" + code + "/join")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions getTable(String token) throws Exception {
        return mockMvc.perform(get("/api/tables/" + tableId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    // ------------------------------------------------------------------
    // Création

    @Test
    @DisplayName("L'hôte crée la table locale : il reçoit le code, possède la table, mais n'y est pas assis")
    void hostCreatesTheTable() throws Exception {
        createLocal(host)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joinCode").value(matchesPattern("[A-HJ-NP-Z2-9]{6}")))
                .andExpect(jsonPath("$.table.local").value(true))
                .andExpect(jsonPath("$.table.ownerId").value(1))
                .andExpect(jsonPath("$.table.status").value("WAITING"))
                .andExpect(jsonPath("$.table.players.length()").value(0));
    }

    @Test
    @DisplayName("Seul un compte crée une table locale : 401 sans jeton, 403 pour un invité")
    void creationRequiresAnAccount() throws Exception {
        mockMvc.perform(post("/api/local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOCAL_TABLE))
                .andExpect(status().isUnauthorized());

        String guest = tokenService.issueGuest(tokenService.newGuestId(), "Mamie").value();
        createLocal(guest).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("La table locale n'est pas dans le lobby, et son code n'apparaît jamais dans sa vue publique")
    void tableIsHiddenAndCodeIsSecret() throws Exception {
        String code = createLocalTable();

        mockMvc.perform(get("/api/tables").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        getTable(alice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.local").value(true))
                .andExpect(jsonPath("$.joinCode").doesNotExist())
                .andExpect(content().string(not(containsString(code))));
    }

    // ------------------------------------------------------------------
    // Invités

    @Test
    @DisplayName("Un invité s'assoit avec le code et un pseudo : il reçoit un jeton d'invité à identifiant négatif")
    void guestJoins() throws Exception {
        String code = createLocalTable();

        String json = joinAsGuest(code, "Mamie")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("Mamie"))
                .andExpect(jsonPath("$.table.players[0].username").value("Mamie"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // Number puis longValue : selon sa taille, le nombre est lu comme un Integer ou un Long
        long guestId = ((Number) JsonPath.read(json, "$.user.id")).longValue();
        long seatedId = ((Number) JsonPath.read(json, "$.table.players[0].userId")).longValue();
        assertTrue(guestId < 0, "Un invité a un identifiant négatif : " + guestId);
        assertEquals(guestId, seatedId, "L'invité assis est bien celui du jeton");
        assertEquals(tableId, ((Number) JsonPath.read(json, "$.table.id")).longValue());

        // Le jeton reçu fonctionne… avec les droits d'un invité seulement
        String guest = JsonPath.read(json, "$.token");
        getTable(guest).andExpect(status().isOk());
        mockMvc.perform(get("/api/tables").header(HttpHeaders.AUTHORIZATION, "Bearer " + guest))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Le code se saisit en minuscules comme en majuscules")
    void codeIsCaseInsensitive() throws Exception {
        String code = createLocalTable();

        joinAsGuest(code.toLowerCase(), "Mamie").andExpect(status().isOk());
    }

    @Test
    @DisplayName("Code inconnu : 404, et aucun jeton n'est distribué")
    void unknownCode() throws Exception {
        joinAsGuest("ZZZZZZ", "Mamie")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Code inconnu"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("Un pseudo invalide est refusé par la validation des champs")
    void invalidUsername() throws Exception {
        String code = createLocalTable();

        joinAsGuest(code, "a b")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists());
    }

    @Test
    @DisplayName("Deux joueurs ne peuvent pas avoir le même pseudo à la table, majuscules comprises")
    void duplicateUsername() throws Exception {
        String code = createLocalTable();
        joinAsGuest(code, "Mamie").andExpect(status().isOk());

        joinAsGuest(code, "mamie").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Un jeton invalide envoyé à la route invité la fait échouer (401) : le client ne doit pas l'envoyer")
    void invalidTokenBreaksTheGuestRoute() throws Exception {
        String code = createLocalTable();

        // permitAll ne veut pas dire « ignore le jeton » : un jeton présent est vérifié, et refusé s'il est invalide
        mockMvc.perform(post("/api/local/" + code + "/guest")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer jeton-perime")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "Mamie"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un invité peut quitter la salle d'attente")
    void guestLeavesTheWaitingRoom() throws Exception {
        String code = createLocalTable();
        String guest = guestToken(code, "Mamie");

        mockMvc.perform(post("/api/tables/" + tableId + "/leave").header(HttpHeaders.AUTHORIZATION, "Bearer " + guest))
                .andExpect(status().isNoContent());

        getTable(host).andExpect(jsonPath("$.players.length()").value(0));
    }

    // ------------------------------------------------------------------
    // Comptes

    @Test
    @DisplayName("Un compte s'assoit avec le code ; un invité ne peut pas utiliser cette route")
    void accountJoinsWithCode() throws Exception {
        String code = createLocalTable();

        joinWithCode(alice, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.players[0].username").value("Alice"));

        String guest = tokenService.issueGuest(tokenService.newGuestId(), "Mamie").value();
        joinWithCode(guest, code).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Une table locale ne se rejoint pas par son identifiant, depuis le lobby")
    void lobbyJoinIsRefused() throws Exception {
        createLocalTable();

        mockMvc.perform(post("/api/tables/" + tableId + "/join").header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------
    // Lancement

    @Test
    @DisplayName("L'hôte lance la partie d'une invitée et d'un compte, sans être assis lui-même")
    void hostStartsTheGame() throws Exception {
        String code = createLocalTable();
        joinAsGuest(code, "Mamie").andExpect(status().isOk());
        joinWithCode(alice, code).andExpect(status().isOk());

        mockMvc.perform(post("/api/tables/" + tableId + "/start").header(HttpHeaders.AUTHORIZATION, "Bearer " + host))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLAYING"))
                .andExpect(jsonPath("$.players.length()").value(2));
        assertTrue(gameService.isRunning(tableId));

        // La partie a commencé : le code ne permet plus de s'asseoir
        joinAsGuest(code, "Papy").andExpect(status().isConflict());
    }
}