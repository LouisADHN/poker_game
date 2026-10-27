package fr.louis.poker.server.game;

import fr.louis.poker.server.lobby.Seat;
import fr.louis.poker.server.lobby.TableSettings;
import fr.louis.poker.server.lobby.TableStatus;
import fr.louis.poker.server.lobby.TableView;
import fr.louis.poker.server.stats.GameRecord;
import fr.louis.poker.server.stats.GameResult;
import fr.louis.poker.server.stats.GameResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Le résultat d'une partie est enregistré pour une table du lobby, jamais pour une table locale.
 *
 * Pas de base de données ici : on remplace GameResultService par une fausse version
 * qui se contente de noter ce qu'on lui demande d'enregistrer.
 */
class GameServiceResultTest {

    private static final GameResult RESULT = new GameResult("Soirée poker", Instant.now(), 12, List.of(
            new GameResult.PlayerResult(1L, 1, 12, 7),
            new GameResult.PlayerResult(-42L, 2, 12, 5))); // un invité

    private final List<GameResult> recorded = new ArrayList<>();
    private GameService gameService;

    @BeforeEach
    void setUp() {
        // Classe anonyme : une sous-classe sans nom, qui redéfinit record() pour ne rien écrire en base
        GameResultService fakeResults = new GameResultService(null) {
            @Override
            public GameRecord record(GameResult result) {
                recorded.add(result);
                return null;
            }
        };
        // Seul gameResultService sert à resultRecorderFor : les autres dépendances restent vides
        gameService = new GameService(null, null, null, fakeResults, null);
    }

    private static TableView table(boolean local) {
        return new TableView(1L, "Soirée poker", new TableSettings(10, 20, 1000, 6), 1L,
                TableStatus.PLAYING, local, List.of(new Seat(2L, "Alice"), new Seat(-42L, "Mamie")));
    }

    @Test
    @DisplayName("Une partie du lobby est enregistrée")
    void lobbyGameIsRecorded() {
        gameService.resultRecorderFor(table(false)).accept(RESULT);

        assertEquals(List.of(RESULT), recorded);
    }

    @Test
    @DisplayName("Une partie locale n'est jamais enregistrée")
    void localGameIsNotRecorded() {
        gameService.resultRecorderFor(table(true)).accept(RESULT);

        assertEquals(List.of(), recorded);
    }
}