package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.action.LegalActions;
import fr.louis.poker.controller.GameView;
import fr.louis.poker.controller.PlayerController;
import fr.louis.poker.engine.GameEngine;
import fr.louis.poker.engine.Street;
import fr.louis.poker.evaluation.HandEvaluator;
import fr.louis.poker.event.GameEvent;
import fr.louis.poker.model.Card;
import fr.louis.poker.model.Player;
import fr.louis.poker.model.PlayerStatus;
import fr.louis.poker.model.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de la projection TableState.
 *
 * Les premiers tests lui envoient des événements écrits à la main.
 * Le dernier la branche sur le vrai moteur, avec des joueurs qui jouent au hasard,
 * et vérifie après CHAQUE événement qu'elle est d'accord avec le moteur.
 */
class TableStateTest {

    private TableState state;

    @BeforeEach
    void setUp() {
        state = new TableState(List.of("Alice", "Bob"), 1000);
    }

    private TableSnapshot.PlayerSnapshot player(String name) {
        return state.snapshot().players().stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** Début d'une main en tête-à-tête : Alice au bouton (small blind), Bob big blind. */
    private void startHand(int number) {
        state.onEvent(new GameEvent.HandStarted(number, "Alice"));
        state.onEvent(new GameEvent.BlindPosted("Alice", 10));
        state.onEvent(new GameEvent.BlindPosted("Bob", 20));
        state.onEvent(new GameEvent.HoleCardsDealt("Alice", Card.parseAll("As Kd")));
        state.onEvent(new GameEvent.HoleCardsDealt("Bob", Card.parseAll("7c 2h")));
    }

    // ------------------------------------------------------------------
    // Événements écrits à la main

    @Test
    @DisplayName("Avant la première main : tapis de départ, pot vide, pas de dealer")
    void initialState() {
        TableSnapshot snapshot = state.snapshot();

        assertEquals(0, snapshot.handNumber());
        assertNull(snapshot.dealer());
        assertNull(snapshot.street());
        assertEquals(0, snapshot.pot());
        assertEquals(List.of("Alice", "Bob"), snapshot.players().stream().map(TableSnapshot.PlayerSnapshot::name).toList());
        assertEquals(1000, player("Alice").chips());
        assertEquals(PlayerStatus.ACTIVE, player("Bob").status());
    }

    @Test
    @DisplayName("Les blinds passent des tapis au pot")
    void blinds() {
        startHand(1);

        TableSnapshot snapshot = state.snapshot();
        assertEquals(1, snapshot.handNumber());
        assertEquals("Alice", snapshot.dealer());
        assertEquals(Street.PREFLOP, snapshot.street());
        assertEquals(30, snapshot.pot());
        assertEquals(990, player("Alice").chips());
        assertEquals(10, player("Alice").streetBet());
        assertEquals(980, player("Bob").chips());
    }

    @Test
    @DisplayName("Une relance et un suivi : tapis, mises, pot et dernière action")
    void raiseAndCall() {
        startHand(1);
        state.onEvent(new GameEvent.PlayerActed("Alice", new Action.Raise(60), 60, 940));
        state.onEvent(new GameEvent.PlayerActed("Bob", new Action.Call(), 60, 940));

        assertEquals(120, state.snapshot().pot());
        assertEquals(940, player("Alice").chips());
        assertEquals(60, player("Alice").streetBet());
        assertEquals(Map.of("type", "RAISE", "total", 60), player("Alice").lastAction());
        assertEquals(Map.of("type", "CALL"), player("Bob").lastAction());
    }

    @Test
    @DisplayName("Le flop remet les mises du tour à zéro, sans toucher au pot")
    void newStreetResetsBets() {
        startHand(1);
        state.onEvent(new GameEvent.PlayerActed("Alice", new Action.Call(), 20, 980));
        state.onEvent(new GameEvent.PlayerActed("Bob", new Action.Check(), 20, 980));

        state.onEvent(new GameEvent.BoardDealt(Street.FLOP, Card.parseAll("Ah 7d 2c")));

        TableSnapshot snapshot = state.snapshot();
        assertEquals(Street.FLOP, snapshot.street());
        assertEquals(List.of("Ah", "7d", "2c"), snapshot.board());
        assertEquals(40, snapshot.pot());
        assertEquals(0, player("Alice").streetBet());
        assertNull(player("Bob").lastAction());
    }

    @Test
    @DisplayName("Se coucher, puis le pot va au gagnant")
    void foldAndPotWon() {
        startHand(1);
        state.onEvent(new GameEvent.PlayerActed("Alice", new Action.Fold(), 10, 990));
        state.onEvent(new GameEvent.PotWon("Bob", 30));
        state.onEvent(new GameEvent.HandEnded(1));

        assertEquals(PlayerStatus.FOLDED, player("Alice").status());
        assertEquals(1010, player("Bob").chips());
        assertEquals(0, state.snapshot().pot());
    }

    @Test
    @DisplayName("Les cartes privées ne sont jamais dans la photographie publique")
    void holeCardsArePrivate() {
        startHand(1);

        assertEquals(List.of("As", "Kd"), state.holeCardsOf("Alice"));
        assertEquals(List.of("7c", "2h"), state.holeCardsOf("Bob"));
        assertFalse(state.snapshot().toString().contains("Kd"), "Une carte privée apparaît dans la photographie !");
    }

    @Test
    @DisplayName("Les cartes privées sont oubliées à la main suivante")
    void holeCardsAreClearedAtNextHand() {
        startHand(1);
        state.onEvent(new GameEvent.HandStarted(2, "Bob"));

        assertEquals(List.of(), state.holeCardsOf("Alice"));
    }

    @Test
    @DisplayName("Showdown : cartes montrées et catégorie, effacées à la main suivante")
    void showdown() {
        startHand(1);
        List<Card> aliceCards = Card.parseAll("As Kd");
        List<Card> board = Card.parseAll("Ah Ad 7c 2s 9h");
        List<Card> all = new ArrayList<>(aliceCards);
        all.addAll(board);
        state.onEvent(new GameEvent.HandRevealed("Alice", aliceCards, HandEvaluator.evaluate(all)));

        assertEquals(List.of("As", "Kd"), player("Alice").revealedCards());
        assertEquals("THREE_OF_A_KIND", player("Alice").category());

        state.onEvent(new GameEvent.HandStarted(2, "Bob"));
        assertNull(player("Alice").revealedCards());
        assertNull(player("Alice").category());
    }

    @Test
    @DisplayName("Un joueur sans jetons est éliminé à la main suivante")
    void bustedPlayerIsOut() {
        startHand(1);
        state.onEvent(new GameEvent.PlayerActed("Alice", new Action.AllIn(), 1000, 0));
        assertEquals(PlayerStatus.ALL_IN, player("Alice").status());

        state.onEvent(new GameEvent.PlayerActed("Bob", new Action.Call(), 1000, 0));
        state.onEvent(new GameEvent.PotWon("Bob", 2000));
        state.onEvent(new GameEvent.HandStarted(2, "Bob"));

        assertEquals(PlayerStatus.OUT, player("Alice").status());
        assertEquals(PlayerStatus.ACTIVE, player("Bob").status());
    }

    // ------------------------------------------------------------------
    // Branchée sur le vrai moteur

    /**
     * Un joueur qui choisit au hasard parmi ses actions : surtout des checks, suivis et relances,
     * parfois un tapis. Les tapis provoquent des pots secondaires et des éliminations,
     * mais trop fréquents, ils finiraient la partie en quelques mains.
     */
    private static PlayerController randomPlayer(Random random) {
        return view -> {
            LegalActions legal = view.legalActions();
            int roll = random.nextInt(20);
            if (roll < 3) {
                return new Action.Fold();
            }
            if (roll < 12 || (roll < 19 && !legal.canRaise())) {
                return legal.canCheck() ? new Action.Check() : new Action.Call();
            }
            if (roll < 19) {
                // Relance modeste : entre le minimum et trois fois le minimum (sans dépasser le tapis)
                int max = Math.min(legal.maxRaiseTo(), legal.minRaiseTo() * 3);
                int total = legal.minRaiseTo() + random.nextInt(max - legal.minRaiseTo() + 1);
                return view.currentBet() == 0 ? new Action.Bet(total) : new Action.Raise(total);
            }
            return new Action.AllIn();
        };
    }

    @ParameterizedTest(name = "graine {0}")
    @ValueSource(longs = {1, 2, 3, 42, 2026})
    @DisplayName("La projection reste d'accord avec le moteur après chaque événement, sur dix parties entières")
    void followsTheRealEngine(long seed) {
        // Une graine fixe : en cas d'échec, la même partie se rejoue à l'identique
        Random random = new Random(seed);
        int eventsChecked = 0;
        for (int game = 0; game < 10; game++) {
            eventsChecked += playAndCompare(random);
        }
        assertTrue(eventsChecked > 1000, "Trop peu d'événements vérifiés : " + eventsChecked);
    }

    /**
     * Joue une partie complète (au plus 300 mains) à 5 joueurs au hasard,
     * en comparant la projection au moteur après chaque événement.
     *
     * @return le nombre d'événements vérifiés
     */
    private static int playAndCompare(Random random) {
        List<String> names = List.of("Alice", "Bob", "Carol", "Dave", "Eve");
        int startingChips = 2000;

        Table table = new Table(5, 10);
        Map<Player, PlayerController> controllers = new HashMap<>();
        List<Player> enginePlayers = new ArrayList<>();
        for (String name : names) {
            Player player = new Player(name, startingChips);
            table.addPlayer(player);
            enginePlayers.add(player);
            controllers.put(player, randomPlayer(random));
        }

        TableState projection = new TableState(names, startingChips);
        GameEngine engine = new GameEngine(table, controllers, random);
        engine.addListener(projection);

        // Second listener, appelé APRÈS la projection : compare avec la vérité du moteur
        int[] eventsChecked = {0};
        engine.addListener(event -> {
            TableSnapshot snapshot = projection.snapshot();
            int total = snapshot.pot();
            for (Player truth : enginePlayers) {
                TableSnapshot.PlayerSnapshot seen = snapshot.players().stream()
                        .filter(p -> p.name().equals(truth.getName())).findFirst().orElseThrow();
                assertEquals(truth.getChips(), seen.chips(), "Tapis de " + truth.getName() + " après " + event);
                assertEquals(truth.getStatus(), seen.status(), "Statut de " + truth.getName() + " après " + event);
                total += seen.chips();
            }
            assertEquals(names.size() * startingChips, total, "Des jetons sont apparus ou ont disparu après " + event);
            eventsChecked[0]++;
        });

        for (int hand = 0; hand < 300 && !engine.isGameOver(); hand++) {
            engine.playHand();
        }
        return eventsChecked[0];
    }
}