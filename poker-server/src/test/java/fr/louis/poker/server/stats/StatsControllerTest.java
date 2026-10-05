package fr.louis.poker.server.stats;

import fr.louis.poker.server.TestcontainersConfiguration;
import fr.louis.poker.server.security.TokenService;
import fr.louis.poker.server.stats.GameResult.PlayerResult;
import fr.louis.poker.server.user.UserAccount;
import fr.louis.poker.server.user.UserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Jeu de données (construit avant chaque test, annulé après) :
 *
 *   Partie 1 (10 mains) : Alice 1re (5 gagnées), Bob 2e (3), Carol 3e (2)
 *   Partie 2 (6 mains)  : Bob 1er (4),  Alice 2e (2)
 *   Partie 3 (8 mains)  : Alice 1re (5), Bob 2e (3)
 *   Partie 4 (4 mains)  : Dave 1er (3), Carol 2e (1)
 *   Eve est inscrite mais n'a jamais joué.
 *
 * Résultat attendu :
 *   Alice : 3 parties, 2 victoires, 24 mains jouées, 12 gagnées
 *   Dave  : 1 partie,  1 victoire  (taux 100 %)
 *   Bob   : 3 parties, 1 victoire  (taux 33 %), 24 mains jouées, 10 gagnées
 *   Carol : 2 parties, 0 victoire
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class StatsControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserService userService;
    @Autowired private TokenService tokenService;
    @Autowired private GameResultService gameResultService;

    @PersistenceContext
    private EntityManager entityManager;

    private UserAccount alice;
    private UserAccount eve;

    @BeforeEach
    void setUp() {
        alice = userService.register("Alice", "motdepasse123");
        UserAccount bob = userService.register("Bob", "motdepasse123");
        UserAccount carol = userService.register("Carol", "motdepasse123");
        UserAccount dave = userService.register("Dave", "motdepasse123");
        eve = userService.register("Eve", "motdepasse123");

        record(10, new PlayerResult(alice.getId(), 1, 10, 5),
                new PlayerResult(bob.getId(), 2, 10, 3),
                new PlayerResult(carol.getId(), 3, 10, 2));
        record(6, new PlayerResult(bob.getId(), 1, 6, 4),
                new PlayerResult(alice.getId(), 2, 6, 2));
        record(8, new PlayerResult(alice.getId(), 1, 8, 5),
                new PlayerResult(bob.getId(), 2, 8, 3));
        record(4, new PlayerResult(dave.getId(), 1, 4, 3),
                new PlayerResult(carol.getId(), 2, 4, 1));

        // Les requêtes natives lisent la base : on y écrit d'abord tout ce qui est en attente
        entityManager.flush();
    }

    private void record(int hands, PlayerResult... players) {
        gameResultService.record(new GameResult("Test", Instant.now(), hands, List.of(players)));
    }

    private ResultActions getAs(UserAccount user, String path) throws Exception {
        return mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.issue(user).value()));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Classement : victoires, puis taux de victoire, et seulement les joueurs ayant joué")
    void leaderboardOrder() throws Exception {
        getAs(eve, "/api/leaderboard")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4)) // Eve n'apparaît pas
                .andExpect(jsonPath("$[0].username").value("Alice"))
                .andExpect(jsonPath("$[1].username").value("Dave"))  // 1 victoire, taux de 100 %
                .andExpect(jsonPath("$[2].username").value("Bob"))   // 1 victoire, taux de 33 %
                .andExpect(jsonPath("$[3].username").value("Carol"));
    }

    @Test
    @DisplayName("Classement : les compteurs et le taux de victoire sont exacts")
    void leaderboardValues() throws Exception {
        getAs(eve, "/api/leaderboard")
                .andExpect(jsonPath("$[0].gamesPlayed").value(3))
                .andExpect(jsonPath("$[0].wins").value(2))
                .andExpect(jsonPath("$[0].handsPlayed").value(24))
                .andExpect(jsonPath("$[0].handsWon").value(12))
                .andExpect(jsonPath("$[0].winRate").value(closeTo(2.0 / 3, 0.001)))
                .andExpect(jsonPath("$[2].handsWon").value(10))
                .andExpect(jsonPath("$[3].wins").value(0))
                .andExpect(jsonPath("$[3].winRate").value(0.0));
    }

    @Test
    @DisplayName("Le paramètre limit réduit le classement")
    void leaderboardLimit() throws Exception {
        getAs(eve, "/api/leaderboard?limit=2")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].username").value("Dave"));
    }

    @Test
    @DisplayName("Mes statistiques")
    void myStats() throws Exception {
        getAs(alice, "/api/users/me/stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("Alice"))
                .andExpect(jsonPath("$.gamesPlayed").value(3))
                .andExpect(jsonPath("$.wins").value(2))
                .andExpect(jsonPath("$.handsPlayed").value(24))
                .andExpect(jsonPath("$.handsWon").value(12));
    }

    @Test
    @DisplayName("Un joueur qui n'a jamais joué a des statistiques à zéro, et non une erreur")
    void statsWithoutAnyGame() throws Exception {
        getAs(eve, "/api/users/me/stats")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("Eve"))
                .andExpect(jsonPath("$.gamesPlayed").value(0))
                .andExpect(jsonPath("$.wins").value(0))
                .andExpect(jsonPath("$.winRate").value(0.0));
    }

    @Test
    @DisplayName("Le classement nécessite d'être connecté")
    void leaderboardRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/leaderboard")).andExpect(status().isUnauthorized());
    }
}