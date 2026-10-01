package fr.louis.poker.server.lobby;

import fr.louis.poker.server.TestcontainersConfiguration;
import fr.louis.poker.server.security.TokenService;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests des routes du lobby.
 * Les jetons sont fabriqués directement : le lobby n'a besoin que de l'identifiant
 * et du pseudo contenus dans le jeton, pas d'un vrai compte en base.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LobbyControllerTest {

    /** Table de 2 joueurs maximum : pratique pour tester une table pleine. */
    private static final String TWO_PLAYER_TABLE = """
            {"name": "Table des amis", "smallBlind": 10, "bigBlind": 20,
             "startingChips": 1000, "maxPlayers": 2}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private LobbyService lobbyService;

    private String alice;
    private String bob;
    private String carol;

    @BeforeEach
    void setUp() {
        lobbyService.clear();
        alice = tokenFor(1L, "Alice");
        bob = tokenFor(2L, "Bob");
        carol = tokenFor(3L, "Carol");
    }

    @AfterEach
    void tearDown() {
        lobbyService.clear();
    }

    private String tokenFor(long userId, String username) {
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

    private ResultActions create(String token, String json) throws Exception {
        return mockMvc.perform(post("/api/tables")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions join(String token, long tableId) throws Exception {
        return mockMvc.perform(post("/api/tables/" + tableId + "/join")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions leave(String token, long tableId) throws Exception {
        return mockMvc.perform(post("/api/tables/" + tableId + "/leave")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions getTable(String token, long tableId) throws Exception {
        return mockMvc.perform(get("/api/tables/" + tableId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /** Crée la table de 2 joueurs et renvoie son identifiant (le lobby est vidé avant chaque test). */
    private long createTwoPlayerTable(String token) throws Exception {
        create(token, TWO_PLAYER_TABLE).andExpect(status().isCreated());
        return lobbyService.list().getLast().id();
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Création de table")
    class Create {

        @Test
        @DisplayName("Le créateur est assis et propriétaire de la table")
        void creatorIsSeatedAndOwner() throws Exception {
            create(alice, TWO_PLAYER_TABLE)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Table des amis"))
                    .andExpect(jsonPath("$.ownerId").value(1))
                    .andExpect(jsonPath("$.status").value("WAITING"))
                    .andExpect(jsonPath("$.settings.bigBlind").value(20))
                    .andExpect(jsonPath("$.players.length()").value(1))
                    .andExpect(jsonPath("$.players[0].username").value("Alice"));
        }

        @Test
        @DisplayName("Sans jeton : 401")
        void requiresAuthentication() throws Exception {
            mockMvc.perform(post("/api/tables")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(TWO_PLAYER_TABLE))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Un nom vide est refusé par la validation des champs")
        void blankNameIsRejected() throws Exception {
            create(alice, """
                    {"name": "", "smallBlind": 10, "bigBlind": 20, "startingChips": 1000, "maxPlayers": 4}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.name").exists());
        }

        @Test
        @DisplayName("Une small blind supérieure à la big blind est refusée")
        void smallBlindAboveBigBlindIsRejected() throws Exception {
            create(alice, """
                    {"name": "Test", "smallBlind": 50, "bigBlind": 20, "startingChips": 1000, "maxPlayers": 4}
                    """)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Paramètres invalides"))
                    .andExpect(jsonPath("$.detail").isNotEmpty());
        }

        @Test
        @DisplayName("Un tapis de moins de 10 big blinds est refusé, 10 big blinds exactement est accepté")
        void startingChipsBoundary() throws Exception {
            create(alice, """
                    {"name": "Test", "smallBlind": 10, "bigBlind": 20, "startingChips": 199, "maxPlayers": 4}
                    """)
                    .andExpect(status().isBadRequest());

            create(alice, """
                    {"name": "Test", "smallBlind": 10, "bigBlind": 20, "startingChips": 200, "maxPlayers": 4}
                    """)
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("La table créée apparaît dans la liste")
        void createdTableIsListed() throws Exception {
            createTwoPlayerTable(alice);

            mockMvc.perform(get("/api/tables").header(HttpHeaders.AUTHORIZATION, "Bearer " + bob))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].name").value("Table des amis"));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Rejoindre une table")
    class Join {

        @Test
        @DisplayName("Un joueur rejoint une table")
        void playerJoins() throws Exception {
            long tableId = createTwoPlayerTable(alice);

            join(bob, tableId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.players.length()").value(2))
                    .andExpect(jsonPath("$.players[1].username").value("Bob"));
        }

        @Test
        @DisplayName("Une table pleine refuse un nouveau joueur")
        void fullTableIsRejected() throws Exception {
            long tableId = createTwoPlayerTable(alice);
            join(bob, tableId).andExpect(status().isOk());

            join(carol, tableId)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Action impossible"));
        }

        @Test
        @DisplayName("Une table inexistante renvoie 404")
        void unknownTable() throws Exception {
            join(bob, 999).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Rejoindre deux fois la même table ne change rien")
        void joiningTwiceIsIdempotent() throws Exception {
            long tableId = createTwoPlayerTable(alice);
            join(bob, tableId).andExpect(status().isOk());

            join(bob, tableId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.players.length()").value(2));
        }

        @Test
        @DisplayName("Impossible d'être assis à deux tables à la fois")
        void cannotSitAtTwoTables() throws Exception {
            createTwoPlayerTable(alice);
            long bobTable = createTwoPlayerTable(bob);

            join(alice, bobTable).andExpect(status().isConflict());
            create(alice, TWO_PLAYER_TABLE).andExpect(status().isConflict());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Quitter une table")
    class Leave {

        @Test
        @DisplayName("Un joueur quitte la table : 204, et il n'y est plus")
        void playerLeaves() throws Exception {
            long tableId = createTwoPlayerTable(alice);
            join(bob, tableId);

            leave(bob, tableId).andExpect(status().isNoContent());

            getTable(alice, tableId)
                    .andExpect(jsonPath("$.players.length()").value(1))
                    .andExpect(jsonPath("$.players[0].username").value("Alice"));
        }

        @Test
        @DisplayName("Le créateur part : la table revient au joueur suivant")
        void ownerLeavesAndTableIsTransferred() throws Exception {
            long tableId = createTwoPlayerTable(alice);
            join(bob, tableId);

            leave(alice, tableId).andExpect(status().isNoContent());

            getTable(bob, tableId).andExpect(jsonPath("$.ownerId").value(2));
        }

        @Test
        @DisplayName("Le dernier joueur part : la table est supprimée")
        void lastPlayerLeavesAndTableIsRemoved() throws Exception {
            long tableId = createTwoPlayerTable(alice);

            leave(alice, tableId).andExpect(status().isNoContent());

            getTable(alice, tableId).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Quitter une table où l'on n'est pas assis : 409")
        void leavingTableNotSeatedAt() throws Exception {
            long tableId = createTwoPlayerTable(alice);
            leave(bob, tableId).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("Après avoir quitté une table, on peut en rejoindre une autre")
        void canJoinAnotherTableAfterLeaving() throws Exception {
            long aliceTable = createTwoPlayerTable(alice);
            long bobTable = createTwoPlayerTable(bob);
            join(carol, aliceTable);

            leave(carol, aliceTable);
            join(carol, bobTable).andExpect(status().isOk());
        }
    }
}