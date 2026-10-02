package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.action.LegalActions;
import fr.louis.poker.controller.GameView;
import fr.louis.poker.engine.Street;
import fr.louis.poker.model.Card;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du RemotePlayerController.
 * decide() bloque : on l'exécute donc dans un autre thread (CompletableFuture),
 * pendant que le thread du test joue le rôle du joueur qui envoie son action.
 */
class RemotePlayerControllerTest {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(5);

    /** Messages "envoyés" au joueur : en vrai ils partiraient par WebSocket. */
    private BlockingQueue<GameMessage> sentMessages;

    @BeforeEach
    void setUp() {
        sentMessages = new LinkedBlockingQueue<>();
    }

    private RemotePlayerController controller(Duration timeout) {
        return new RemotePlayerController(1L, sentMessages::add, timeout);
    }

    private static GameView sampleView() {
        return new GameView(
                "Louis",
                List.of(Card.of("As"), Card.of("Kd")),
                980,
                Street.PREFLOP,
                List.of(),
                30,
                20,
                0,
                List.of(),
                new LegalActions(false, 20, true, 40, 980));
    }

    /** Lance decide() dans un autre thread et attend que le message "c'est ton tour" soit parti. */
    private CompletableFuture<Action> startDecision(RemotePlayerController controller) throws InterruptedException {
        CompletableFuture<Action> decision = CompletableFuture.supplyAsync(() -> controller.decide(sampleView()));
        GameMessage yourTurn = sentMessages.poll(5, TimeUnit.SECONDS);
        assertNotNull(yourTurn, "Le message YOUR_TURN n'a pas été envoyé");
        assertEquals("YOUR_TURN", yourTurn.type());
        return decision;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("decide() prévient le joueur puis renvoie l'action qu'il soumet")
    void returnsSubmittedAction() throws Exception {
        RemotePlayerController controller = controller(LONG_TIMEOUT);
        CompletableFuture<Action> decision = startDecision(controller);

        assertTrue(controller.isWaiting());
        assertTrue(controller.submit(new Action.Raise(60)));

        assertEquals(new Action.Raise(60), decision.get(5, TimeUnit.SECONDS));
        assertFalse(controller.isWaiting());
    }

    @Test
    @DisplayName("Le message YOUR_TURN contient les cartes du joueur en notation courte")
    void yourTurnContainsHoleCards() throws Exception {
        RemotePlayerController controller = controller(SHORT_TIMEOUT);
        CompletableFuture.runAsync(() -> controller.decide(sampleView()));

        GameMessage yourTurn = sentMessages.poll(5, TimeUnit.SECONDS);
        assertNotNull(yourTurn);
        assertEquals(List.of("As", "Kd"), yourTurn.data().get("holeCards"));
    }

    @Test
    @DisplayName("Sans réponse dans le délai, decide() renvoie null")
    void timeoutReturnsNull() throws Exception {
        RemotePlayerController controller = controller(SHORT_TIMEOUT);
        CompletableFuture<Action> decision = startDecision(controller);

        assertNull(decision.get(5, TimeUnit.SECONDS));
        assertFalse(controller.isWaiting());
    }

    @Test
    @DisplayName("Une action envoyée hors de son tour est refusée")
    void actionOutsideTurnIsRefused() {
        RemotePlayerController controller = controller(SHORT_TIMEOUT);
        assertFalse(controller.isWaiting());
        assertFalse(controller.submit(new Action.Fold()));
    }

    @Test
    @DisplayName("Une action refusée hors de son tour n'est pas utilisée au tour suivant")
    void actionOutsideTurnIsNotReusedLater() throws Exception {
        RemotePlayerController controller = controller(SHORT_TIMEOUT);
        controller.submit(new Action.Fold()); // trop tôt : ignorée

        CompletableFuture<Action> decision = startDecision(controller);
        // Personne ne répond pendant ce tour : le fold envoyé plus tôt ne doit pas ressortir
        assertNull(decision.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Si le thread de la table est interrompu, decide() renvoie null et garde l'interruption")
    void interruptionReturnsNull() throws Exception {
        RemotePlayerController controller = controller(LONG_TIMEOUT);
        BlockingQueue<Boolean> interruptFlag = new LinkedBlockingQueue<>();
        BlockingQueue<Object> result = new LinkedBlockingQueue<>();

        Thread tableThread = new Thread(() -> {
            Action action = controller.decide(sampleView());
            result.add(action == null ? "null" : action);
            interruptFlag.add(Thread.currentThread().isInterrupted());
        });
        tableThread.start();

        assertNotNull(sentMessages.poll(5, TimeUnit.SECONDS));
        tableThread.interrupt();

        assertEquals("null", result.poll(5, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, interruptFlag.poll(5, TimeUnit.SECONDS));
    }
}