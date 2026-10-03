package fr.louis.poker.server.game;

import fr.louis.poker.event.GameEvent;
import fr.louis.poker.model.Card;
import fr.louis.poker.model.Player;
import fr.louis.poker.server.stats.GameResult;
import fr.louis.poker.server.stats.GameResult.PlayerResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du ResultTracker : on rejoue des mains à la main,
 * en modifiant les tapis comme le ferait le moteur (bet / win).
 */
class ResultTrackerTest {

    private static final Instant STARTED_AT = Instant.parse("2026-10-03T20:00:00Z");

    private Player alice;
    private Player bob;
    private Player carol;
    private ResultTracker tracker;

    private void setUpPlayers(int aliceChips, int bobChips, int carolChips) {
        alice = new Player("Alice", aliceChips);
        bob = new Player("Bob", bobChips);
        carol = new Player("Carol", carolChips);
        tracker = new ResultTracker(
                List.of(alice, bob, carol),
                Map.of("Alice", 1L, "Bob", 2L, "Carol", 3L));
    }

    /** Début de main : HandStarted, puis distribution des cartes aux joueurs donnés. */
    private void startHand(int number, Player... dealtPlayers) {
        tracker.onEvent(new GameEvent.HandStarted(number, "Carol"));
        for (Player player : dealtPlayers) {
            tracker.onEvent(new GameEvent.HoleCardsDealt(player.getName(), Card.parseAll("As Kd")));
        }
    }

    private void endHand(int number) {
        tracker.onEvent(new GameEvent.HandEnded(number));
    }

    private static PlayerResult resultOf(GameResult result, long userId) {
        return result.players().stream()
                .filter(p -> p.userId() == userId)
                .findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Éliminations dans des mains différentes : le dernier éliminé est deuxième")
    void eliminationsInDifferentHands() {
        setUpPlayers(100, 50, 100);

        // Main 1 : Bob perd tout contre Carol
        startHand(1, alice, bob, carol);
        bob.bet(50);
        carol.win(50);
        tracker.onEvent(new GameEvent.PotWon("Carol", 50));
        endHand(1);

        // Main 2 : Alice perd tout contre Carol
        startHand(2, alice, carol);
        alice.bet(100);
        carol.win(100);
        tracker.onEvent(new GameEvent.PotWon("Carol", 100));
        endHand(2);

        GameResult result = tracker.toResult("Test", STARTED_AT);

        List<Long> ranking = result.players().stream().map(PlayerResult::userId).toList();
        assertEquals(List.of(3L, 1L, 2L), ranking); // Carol, Alice, Bob
        assertEquals(List.of(1, 2, 3), result.players().stream().map(PlayerResult::position).toList());
        assertEquals(2, result.handsPlayed());
        assertEquals("Test", result.tableName());
        assertEquals(STARTED_AT, result.startedAt());
    }

    @Test
    @DisplayName("Deux éliminations dans la même main : le plus gros tapis de départ est mieux classé")
    void simultaneousEliminationsUseStartingStack() {
        setUpPlayers(100, 50, 1000);

        startHand(1, alice, bob, carol);
        alice.bet(100);
        bob.bet(50);
        carol.win(150);
        tracker.onEvent(new GameEvent.PotWon("Carol", 150));
        endHand(1);

        GameResult result = tracker.toResult("Test", STARTED_AT);

        assertEquals(1, resultOf(result, 3L).position()); // Carol gagne
        assertEquals(2, resultOf(result, 1L).position()); // Alice avait 100 au départ
        assertEquals(3, resultOf(result, 2L).position()); // Bob n'avait que 50
    }

    @Test
    @DisplayName("Mains jouées et gagnées : une main gagnée ne compte qu'une fois, même avec plusieurs pots")
    void handsPlayedAndWon() {
        setUpPlayers(100, 100, 100);

        // Main 1 : Carol remporte le pot principal ET un pot secondaire
        startHand(1, alice, bob, carol);
        tracker.onEvent(new GameEvent.PotWon("Carol", 60));
        tracker.onEvent(new GameEvent.PotWon("Carol", 40));
        endHand(1);

        // Main 2 : Alice gagne
        startHand(2, alice, bob, carol);
        tracker.onEvent(new GameEvent.PotWon("Alice", 30));
        endHand(2);

        // Main 3 : Bob ne reçoit pas de cartes (cas d'un joueur déjà éliminé, par exemple)
        startHand(3, alice, carol);
        tracker.onEvent(new GameEvent.PotWon("Carol", 30));
        endHand(3);

        GameResult result = tracker.toResult("Test", STARTED_AT);

        assertEquals(3, resultOf(result, 1L).handsPlayed());
        assertEquals(1, resultOf(result, 1L).handsWon());
        assertEquals(2, resultOf(result, 2L).handsPlayed());
        assertEquals(0, resultOf(result, 2L).handsWon());
        assertEquals(3, resultOf(result, 3L).handsPlayed());
        assertEquals(2, resultOf(result, 3L).handsWon()); // et non 3 : deux pots dans la main 1
        assertEquals(3, result.handsPlayed());
    }
}