package fr.louis.poker.server.game;

import fr.louis.poker.server.lobby.Seat;
import fr.louis.poker.server.lobby.TableSettings;
import fr.louis.poker.server.stats.GameResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de la fin de partie ABANDONED.
 *
 * Les premiers tests appellent directement isAbandoned(), sans lancer la partie.
 * Les derniers lancent vraiment la partie (start()) : un thread de table tourne,
 * avec une pause très courte entre les mains pour que le test soit rapide.
 */
class GameSessionAbandonTest {

    private static final Long LOUIS = 1L;
    private static final Long ALICE = 2L;
    private static final Long BOB = 3L;

    /** Pause entre deux mains, très courte pour les tests où la partie tourne vraiment. */
    private static final Duration SHORT_PAUSE = Duration.ofMillis(10);

    /**
     * Messages envoyés par la session.
     * CopyOnWriteArrayList : le thread de la table y ajoute pendant que le thread du test les lit.
     */
    private List<Message<?>> sentMessages;

    /** Le résultat transmis à onCompleted, s'il y en a un. */
    private AtomicReference<GameResult> recordedResult;

    /** Ouvert (compte à 0) quand la partie appelle onFinished. */
    private CountDownLatch finished;

    private GameSession session;

    @BeforeEach
    void setUp() {
        sentMessages = new CopyOnWriteArrayList<>();
        recordedResult = new AtomicReference<>();
        finished = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        // Un test qui échoue ne doit pas laisser un thread de table tourner
        if (session != null) {
            session.stop();
        }
    }

    private GameSession sessionWith(Seat... seats) {
        SimpMessagingTemplate messaging = new SimpMessagingTemplate((message, timeout) -> sentMessages.add(message));
        session = new GameSession(
                1L,
                "Table de test",
                List.of(seats),
                new TableSettings(10, 20, 1000, 6),
                // Délai de réflexion long : si la partie attendait un joueur absent, le test le verrait
                new GameProperties(Duration.ofSeconds(30), SHORT_PAUSE),
                messaging,
                recordedResult::set,
                finished::countDown);
        return session;
    }

    private GameSession twoPlayers() {
        return sessionWith(new Seat(LOUIS, "Louis"), new Seat(ALICE, "Alice"));
    }

    private GameSession threePlayers() {
        return sessionWith(new Seat(LOUIS, "Louis"), new Seat(ALICE, "Alice"), new Seat(BOB, "Bob"));
    }

    /** Les messages GAME_OVER envoyés à la table. */
    private List<GameMessage> gameOverMessages() {
        return sentMessages.stream()
                .map(message -> (GameMessage) message.getPayload())
                .filter(message -> message.type().equals("GAME_OVER"))
                .toList();
    }

    /** Vrai si Louis a reçu un YOUR_TURN sur sa file privée. */
    private boolean louisGotHisTurn() {
        String louisQueue = "/user/" + LOUIS + GameSession.PRIVATE_QUEUE;
        return sentMessages.stream().anyMatch(message ->
                louisQueue.equals(SimpMessageHeaderAccessor.getDestination(message.getHeaders()))
                        && ((GameMessage) message.getPayload()).type().equals("YOUR_TURN"));
    }

    // ------------------------------------------------------------------
    // La règle (sans lancer la partie)

    @Test
    @DisplayName("Au lancement, la partie n'est pas abandonnée")
    void notAbandonedAtStart() {
        assertFalse(twoPlayers().isAbandoned());
    }

    @Test
    @DisplayName("Un joueur absent face à un joueur présent : la partie continue")
    void oneAwayIsNotEnough() {
        twoPlayers();
        session.updatePresence(ALICE, Presence.AWAY);
        assertFalse(session.isAbandoned());
    }

    @Test
    @DisplayName("Un joueur en délai de grâce compte comme connecté : la partie continue")
    void disconnectedStillCounts() {
        twoPlayers();
        session.updatePresence(LOUIS, Presence.DISCONNECTED);
        session.updatePresence(ALICE, Presence.AWAY);
        assertFalse(session.isAbandoned());
    }

    @Test
    @DisplayName("Tous les joueurs absents : la partie est abandonnée")
    void everyoneAwayAbandons() {
        twoPlayers();
        session.updatePresence(LOUIS, Presence.AWAY);
        session.updatePresence(ALICE, Presence.AWAY);
        assertTrue(session.isAbandoned());
    }

    @Test
    @DisplayName("À deux, si un joueur quitte la partie, elle est abandonnée")
    void leavingHeadsUpAbandons() {
        twoPlayers();
        session.updatePresence(ALICE, Presence.LEFT);
        assertTrue(session.isAbandoned());
    }

    @Test
    @DisplayName("À trois, un départ laisse deux joueurs : la partie continue")
    void oneLeavingOutOfThreeContinues() {
        threePlayers();
        session.updatePresence(BOB, Presence.LEFT);
        assertFalse(session.isAbandoned());
    }

    @Test
    @DisplayName("À trois, un parti et un absent : il reste un joueur connecté, la partie continue")
    void oneConnectedPlayerIsEnough() {
        threePlayers();
        session.updatePresence(BOB, Presence.LEFT);
        session.updatePresence(ALICE, Presence.AWAY);
        assertFalse(session.isAbandoned());
    }

    // ------------------------------------------------------------------
    // La partie qui tourne vraiment

    @Test
    @DisplayName("Partie lancée avec tous les joueurs absents : GAME_OVER ABANDONED, sans résultat enregistré")
    void abandonedGameEndsWithoutResult() throws Exception {
        twoPlayers();
        session.updatePresence(LOUIS, Presence.AWAY);
        session.updatePresence(ALICE, Presence.AWAY);

        session.start();

        assertTrue(finished.await(2, TimeUnit.SECONDS), "La partie ne s'est pas terminée");
        List<GameMessage> gameOvers = gameOverMessages();
        assertEquals(1, gameOvers.size());
        assertEquals("ABANDONED", gameOvers.getFirst().data().get("reason"));
        assertNull(recordedResult.get(), "Une partie abandonnée ne doit pas compter au classement");
    }

    @Test
    @DisplayName("Le dernier joueur connecté devient absent pendant son tour : la partie s'arrête sans attendre")
    void lastConnectedPlayerGoingAwayEndsTheGame() throws Exception {
        twoPlayers();
        session.updatePresence(ALICE, Presence.AWAY);
        session.start();

        // Louis est encore là : la partie continue, et finit par lui demander de jouer
        long deadline = System.currentTimeMillis() + 2000;
        while (!louisGotHisTurn() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(louisGotHisTurn(), "La partie aurait dû continuer et demander à Louis de jouer");
        assertEquals(1, finished.getCount(), "La partie ne devait pas s'arrêter tant que Louis était là");

        // Louis part à son tour, pendant que la table attend sa décision (délai de 30 s)
        session.updatePresence(LOUIS, Presence.AWAY);

        // 2 s, bien moins que les 30 s : l'attente a été réveillée, la main finie, puis la partie arrêtée
        assertTrue(finished.await(2, TimeUnit.SECONDS), "La partie aurait dû s'arrêter sans attendre le délai");
        assertEquals("ABANDONED", gameOverMessages().getFirst().data().get("reason"));
    }
}