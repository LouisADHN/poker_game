package fr.louis.poker.server.stats;

import fr.louis.poker.server.TestcontainersConfiguration;
import fr.louis.poker.server.stats.GameResult.PlayerResult;
import fr.louis.poker.server.user.UserAccount;
import fr.louis.poker.server.user.UserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class GameResultServiceTest {

    @Autowired
    private GameResultService gameResultService;

    @Autowired
    private GameRecordRepository gameRecordRepository;

    @Autowired
    private UserService userService;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("Un résultat est enregistré avec tous ses participants, et la base date la fin de partie")
    void resultIsSavedWithPlayers() {
        UserAccount alice = userService.register("Alice", "motdepasse123");
        UserAccount bob = userService.register("Bob", "motdepasse123");
        Instant startedAt = Instant.parse("2026-10-03T20:00:00Z");

        GameResult result = new GameResult("Table des amis", startedAt, 12, List.of(
                new PlayerResult(bob.getId(), 1, 12, 7),
                new PlayerResult(alice.getId(), 2, 12, 5)));

        gameResultService.record(result);

        // Relit depuis la base, et non depuis le cache d'Hibernate
        entityManager.flush();
        entityManager.clear();

        List<GameRecord> games = gameRecordRepository.findAllWithPlayers();
        assertEquals(1, games.size());

        GameRecord game = games.getFirst();
        assertNotNull(game.getId());
        assertEquals("Table des amis", game.getTableName());
        assertEquals(startedAt, game.getStartedAt());
        assertEquals(12, game.getHandsPlayed());
        assertNotNull(game.getEndedAt()); // rempli par DEFAULT now()

        List<GamePlayerRecord> players = game.getPlayers().stream()
                .sorted(Comparator.comparingInt(GamePlayerRecord::getPosition))
                .toList();
        assertEquals(2, players.size());

        assertEquals(bob.getId(), players.get(0).getUserId());
        assertEquals(1, players.get(0).getPosition());
        assertEquals(7, players.get(0).getHandsWon());

        assertEquals(alice.getId(), players.get(1).getUserId());
        assertEquals(2, players.get(1).getPosition());
        assertEquals(5, players.get(1).getHandsWon());
    }
}