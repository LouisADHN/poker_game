package fr.louis.poker.engine;

import fr.louis.poker.action.Action;
import fr.louis.poker.controller.PlayerController;
import fr.louis.poker.event.GameEvent;
import fr.louis.poker.model.Player;
import fr.louis.poker.model.PlayerStatus;
import fr.louis.poker.model.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class GameEngineTest {

    // Contrôleurs scriptés
    private static final PlayerController ALWAYS_FOLD = view -> new Action.Fold();
    private static final PlayerController CHECK_OR_CALL =
            view -> view.legalActions().canCheck() ? new Action.Check() : new Action.Call();
    private static final PlayerController ALWAYS_ALL_IN = view -> new Action.AllIn();

    private Table table;
    private Player a;
    private Player b;
    private Player c;
    private List<GameEvent> events;

    @BeforeEach
    void setUp() {
        table = new Table(10, 20);
        a = new Player("A", 1000);
        b = new Player("B", 1000);
        c = new Player("C", 1000);
        events = new ArrayList<>();
    }

    /** Crée un moteur avec une graine fixe et enregistre tous les événements. */
    private GameEngine engine(Map<Player, PlayerController> controllers) {
        GameEngine engine = new GameEngine(table, controllers, new Random(42));
        engine.addListener(events::add);
        return engine;
    }

    private void seatThree() {
        table.addPlayer(a);
        table.addPlayer(b);
        table.addPlayer(c);
    }

    private void seatTwo() {
        table.addPlayer(a);
        table.addPlayer(b);
    }

    private <T extends GameEvent> List<T> eventsOfType(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un joueur sans contrôleur est refusé")
    void missingControllerIsRejected() {
        seatTwo();
        assertThrows(IllegalArgumentException.class,
                () -> new GameEngine(table, Map.of(a, ALWAYS_FOLD), new Random(42)));
    }

    @Test
    @DisplayName("Tout le monde se couche : la big blind remporte les blinds")
    void everyoneFoldsToBigBlind() {
        seatThree(); // Dealer A, small blind B, big blind C
        engine(Map.of(a, ALWAYS_FOLD, b, ALWAYS_FOLD, c, ALWAYS_FOLD)).playHand();

        assertEquals(1000, a.getChips());
        assertEquals(990, b.getChips());
        assertEquals(1010, c.getChips());
        assertEquals(List.of(new GameEvent.PotWon("C", 30)), eventsOfType(GameEvent.PotWon.class));
        assertTrue(eventsOfType(GameEvent.BoardDealt.class).isEmpty());
    }

    @Test
    @DisplayName("Les événements commencent par HandStarted, les blinds, et finissent par HandEnded")
    void eventsAreEmittedInOrder() {
        seatThree();
        engine(Map.of(a, ALWAYS_FOLD, b, ALWAYS_FOLD, c, ALWAYS_FOLD)).playHand();

        assertEquals(new GameEvent.HandStarted(1, "A"), events.getFirst());
        assertEquals(new GameEvent.BlindPosted("B", 10), events.get(1));
        assertEquals(new GameEvent.BlindPosted("C", 20), events.get(2));
        assertEquals(new GameEvent.HandEnded(1), events.getLast());
    }

    @Test
    @DisplayName("Main jouée jusqu'au showdown : board complet, mains révélées, jetons conservés")
    void checkDownToShowdown() {
        seatThree();
        engine(Map.of(a, CHECK_OR_CALL, b, CHECK_OR_CALL, c, CHECK_OR_CALL)).playHand();

        List<GameEvent.BoardDealt> boards = eventsOfType(GameEvent.BoardDealt.class);
        assertEquals(3, boards.size());
        assertEquals(5, boards.getLast().board().size());
        assertEquals(3, boards.getFirst().board().size()); // le flop n'a pas été modifié après coup

        assertEquals(3, eventsOfType(GameEvent.HandRevealed.class).size());

        int distributed = eventsOfType(GameEvent.PotWon.class).stream()
                .mapToInt(GameEvent.PotWon::amount).sum();
        assertEquals(60, distributed);
        assertEquals(3000, a.getChips() + b.getChips() + c.getChips());
    }

    @Test
    @DisplayName("Le bouton avance à chaque main")
    void buttonMovesEachHand() {
        seatThree();
        GameEngine engine = engine(Map.of(a, ALWAYS_FOLD, b, ALWAYS_FOLD, c, ALWAYS_FOLD));
        engine.playHand();
        engine.playHand();

        List<String> dealers = eventsOfType(GameEvent.HandStarted.class).stream()
                .map(GameEvent.HandStarted::dealer)
                .toList();
        assertEquals(List.of("A", "B"), dealers);
        assertEquals(2, engine.getHandNumber());
    }

    @Test
    @DisplayName("Tête-à-tête : le dealer est small blind et parle en premier preflop")
    void headsUpBlindsAndOrder() {
        seatTwo(); // Dealer A
        List<String> askedPlayers = new ArrayList<>();
        PlayerController recordAndFold = view -> {
            askedPlayers.add(view.playerName());
            return new Action.Fold();
        };

        engine(Map.of(a, recordAndFold, b, recordAndFold)).playHand();

        assertEquals(new GameEvent.BlindPosted("A", 10), events.get(1));
        assertEquals(new GameEvent.BlindPosted("B", 20), events.get(2));
        assertEquals(List.of("A"), askedPlayers);
        assertEquals(990, a.getChips());
        assertEquals(1010, b.getChips());
    }

    @Test
    @DisplayName("All-in preflop : le board est déroulé jusqu'à la river sans enchères")
    void allInPreflopRunsOutTheBoard() {
        seatTwo();
        engine(Map.of(a, ALWAYS_ALL_IN, b, CHECK_OR_CALL)).playHand();

        assertEquals(3, eventsOfType(GameEvent.BoardDealt.class).size());
        assertEquals(2, eventsOfType(GameEvent.HandRevealed.class).size());
        assertEquals(2000, a.getChips() + b.getChips());
    }

    @Test
    @DisplayName("Une action illégale est remplacée par Fold face à une mise")
    void illegalActionFallsBackToFold() {
        seatThree();
        PlayerController alwaysCheck = view -> new Action.Check(); // illégal face à la big blind

        engine(Map.of(a, alwaysCheck, b, ALWAYS_FOLD, c, ALWAYS_FOLD)).playHand();

        GameEvent.PlayerActed firstAction = eventsOfType(GameEvent.PlayerActed.class).getFirst();
        assertEquals("A", firstAction.player());
        assertEquals(new Action.Fold(), firstAction.action());
        assertEquals(PlayerStatus.FOLDED, a.getStatus());
    }

    @Test
    @DisplayName("Une action illégale est remplacée par Check quand c'est possible")
    void illegalActionFallsBackToCheck() {
        seatThree();
        PlayerController nullController = view -> null;

        // A et B suivent, C (big blind) répond null : il peut checker
        engine(Map.of(a, CHECK_OR_CALL, b, CHECK_OR_CALL, c, nullController)).playHand();

        GameEvent.PlayerActed cAction = eventsOfType(GameEvent.PlayerActed.class).stream()
                .filter(e -> e.player().equals("C"))
                .findFirst()
                .orElseThrow();
        assertEquals(new Action.Check(), cAction.action());
    }

    @Test
    @DisplayName("La partie est finie quand un seul joueur a encore des jetons")
    void gameOverWhenOnePlayerHasChips() {
        Player broke = new Player("Z", 0);
        table.addPlayer(a);
        table.addPlayer(broke);
        GameEngine engine = engine(Map.of(a, ALWAYS_FOLD, broke, ALWAYS_FOLD));

        assertTrue(engine.isGameOver());
    }

    @Test
    @DisplayName("La partie n'est pas finie au départ")
    void gameNotOverAtStart() {
        seatThree();
        assertFalse(engine(Map.of(a, ALWAYS_FOLD, b, ALWAYS_FOLD, c, ALWAYS_FOLD)).isGameOver());
    }
}