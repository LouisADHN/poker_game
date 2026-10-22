package fr.louis.poker.server.game;

import fr.louis.poker.server.lobby.Seat;
import fr.louis.poker.server.lobby.TableSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du message GAME_STATE : l'état complet de la partie, envoyé à un joueur qui (re)charge sa page.
 */
class GameSessionStateTest {

    private static final Long LOUIS = 1L;
    private static final Long ALICE = 2L;
    /** Un utilisateur qui n'est pas assis à la table : un spectateur */
    private static final Long SPECTATOR = 99L;
    private static final String LOUIS_QUEUE = "/user/" + LOUIS + GameSession.PRIVATE_QUEUE;

    private List<Message<?>> sentMessages;
    private CountDownLatch finished;
    private GameSession session;

    @BeforeEach
    void setUp() {
        sentMessages = new CopyOnWriteArrayList<>();
        finished = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.stop();
        }
    }

    /** Une session à deux joueurs, avec le délai de réflexion donné et 10 ms entre deux mains. */
    private GameSession newSession(Duration turnTimeout) {
        SimpMessagingTemplate messaging = new SimpMessagingTemplate((message, timeout) -> sentMessages.add(message));
        session = new GameSession(
                1L,
                "Table de test",
                List.of(new Seat(LOUIS, "Louis"), new Seat(ALICE, "Alice")),
                new TableSettings(10, 20, 1000, 6),
                new GameProperties(turnTimeout, Duration.ofMillis(10), Duration.ofSeconds(30)),
                messaging,
                result -> { },
                finished::countDown);
        return session;
    }

    private static String destination(Message<?> message) {
        return SimpMessageHeaderAccessor.getDestination(message.getHeaders());
    }

    private static GameMessage payload(Message<?> message) {
        return (GameMessage) message.getPayload();
    }

    /** Le dernier GAME_STATE envoyé à ce joueur. */
    private Map<String, Object> lastStateFor(Long userId) {
        String queue = "/user/" + userId + GameSession.PRIVATE_QUEUE;
        return sentMessages.stream()
                .filter(message -> queue.equals(destination(message)))
                .map(GameSessionStateTest::payload)
                .filter(message -> message.type().equals("GAME_STATE"))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("Aucun GAME_STATE envoyé"))
                .data();
    }

    /** Attend qu'un YOUR_TURN soit parti vers l'un des joueurs, et renvoie son identifiant. */
    private Long waitForSomeoneToPlay() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            for (Message<?> message : sentMessages) {
                if (payload(message).type().equals("YOUR_TURN")) {
                    return destination(message).equals(LOUIS_QUEUE) ? LOUIS : ALICE;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Personne n'a eu la main");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Un spectateur reçoit l'état public, marqué spectateur, sans cartes ni tour")
    void spectatorGetsThePublicState() {
        newSession(Duration.ofSeconds(30));

        session.sendStateTo(SPECTATOR);

        Map<String, Object> state = lastStateFor(SPECTATOR);
        assertEquals(true, state.get("spectator"));
        assertEquals(List.of(), state.get("holeCards"));
        assertNull(state.get("yourTurn"));
        assertEquals(Map.of("Louis", "PRESENT", "Alice", "PRESENT"), state.get("presences"));
    }

    @Test
    @DisplayName("En pleine main, un spectateur ne reçoit AUCUNE carte privée des joueurs")
    void spectatorNeverSeesHoleCards() throws Exception {
        newSession(Duration.ofSeconds(5)); // la main attend vraiment : les cartes sont distribuées et cachées
        session.start();
        waitForSomeoneToPlay();

        session.sendStateTo(SPECTATOR);

        // Toutes les cartes privées distribuées pendant la partie…
        List<String> holeCards = sentMessages.stream()
                .map(GameSessionStateTest::payload)
                .filter(message -> message.type().equals("HOLE_CARDS"))
                .flatMap(message -> ((List<?>) message.data().get("cards")).stream())
                .map(Object::toString)
                .toList();
        assertEquals(4, holeCards.size(), "Deux joueurs, deux cartes chacun");

        // … n'apparaissent nulle part dans ce que reçoit le spectateur
        String spectatorState = lastStateFor(SPECTATOR).toString();
        for (String card : holeCards) {
            assertFalse(spectatorState.contains(card), "Le spectateur voit la carte " + card + " !");
        }
        assertNull(lastStateFor(SPECTATOR).get("yourTurn"));
    }

    @Test
    @DisplayName("Un spectateur ne reçoit aucun message privé d'un joueur")
    void spectatorReceivesNoPrivateMessages() throws Exception {
        newSession(Duration.ofMillis(20));
        session.start();
        waitForSomeoneToPlay();
        session.stop();
        assertTrue(finished.await(5, TimeUnit.SECONDS));

        String spectatorQueue = "/user/" + SPECTATOR + GameSession.PRIVATE_QUEUE;
        assertTrue(sentMessages.stream().noneMatch(message -> spectatorQueue.equals(destination(message))));
    }

    @Test
    @DisplayName("Avant la première main : numéro 0, tout le monde présent, ni cartes ni tour")
    void stateBeforeTheFirstHand() {
        newSession(Duration.ofSeconds(30));

        session.sendStateTo(LOUIS);

        Message<?> message = sentMessages.getFirst();
        assertEquals(LOUIS_QUEUE, destination(message), "GAME_STATE doit partir en message privé");
        Map<String, Object> state = payload(message).data();
        assertEquals(0L, state.get("seq"));
        assertEquals(false, state.get("spectator"));
        assertEquals(0, ((TableSnapshot) state.get("table")).handNumber());
        assertEquals(Map.of("Louis", "PRESENT", "Alice", "PRESENT"), state.get("presences"));
        assertEquals(List.of(), state.get("holeCards"));
        assertNull(state.get("yourTurn"));
    }

    @Test
    @DisplayName("Les présences sont dans l'état")
    void presencesAreIncluded() {
        newSession(Duration.ofSeconds(30));
        session.updatePresence(ALICE, Presence.AWAY);

        session.sendStateTo(LOUIS);

        assertEquals(Map.of("Louis", "PRESENT", "Alice", "AWAY"), lastStateFor(LOUIS).get("presences"));
    }

    @Test
    @DisplayName("En pleine main : chacun reçoit ses cartes, et seul celui qui a la main reçoit son tour")
    void stateDuringAHand() throws Exception {
        newSession(Duration.ofSeconds(5)); // long délai : la main attend vraiment le joueur
        session.start();
        Long playing = waitForSomeoneToPlay();
        Long waiting = playing.equals(LOUIS) ? ALICE : LOUIS;

        session.sendStateTo(playing);
        session.sendStateTo(waiting);

        Map<String, Object> playingState = lastStateFor(playing);
        @SuppressWarnings("unchecked")
        Map<String, Object> turn = (Map<String, Object>) playingState.get("yourTurn");
        assertNotNull(turn, "Le joueur qui a la main doit retrouver son tour");
        assertTrue((long) turn.get("timeLeftMs") <= 5000);
        assertEquals(1, ((TableSnapshot) playingState.get("table")).handNumber());

        Map<String, Object> waitingState = lastStateFor(waiting);
        assertNull(waitingState.get("yourTurn"));

        // Chacun a ses deux cartes… et ce ne sont pas celles de l'autre
        List<?> playingCards = (List<?>) playingState.get("holeCards");
        List<?> waitingCards = (List<?>) waitingState.get("holeCards");
        assertEquals(2, playingCards.size());
        assertEquals(2, waitingCards.size());
        assertNotEquals(playingCards, waitingCards);
    }

    @Test
    @DisplayName("Tout ce qui part avant GAME_STATE y est inclus, tout ce qui part après est plus récent")
    void gameStateSplitsTheStreamExactly() throws Exception {
        newSession(Duration.ofMillis(20)); // personne ne répond : des mains en rafale
        // Pendant que la partie tourne, Louis redemande l'état sans arrêt
        Thread reloader = Thread.ofVirtual().start(() -> {
            while (finished.getCount() > 0) {
                session.sendStateTo(LOUIS);
                LockSupport.parkNanos(500_000);
            }
        });

        session.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (sentMessages.stream().filter(m -> payload(m).type().equals("HAND_ENDED")).count() < 10
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        session.stop();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        reloader.join(5000);

        // Ce que reçoit Louis, dans l'ordre d'envoi : les messages de la table et sa file privée
        long tableMessagesSoFar = 0;
        int statesChecked = 0;
        for (Message<?> message : sentMessages) {
            String destination = destination(message);
            if (GameSession.gameTopic(1L).equals(destination)) {
                tableMessagesSoFar++;
            } else if (LOUIS_QUEUE.equals(destination) && payload(message).type().equals("GAME_STATE")) {
                // Son numéro doit être exactement le nombre de messages publics partis avant lui
                assertEquals(tableMessagesSoFar, payload(message).data().get("seq"));
                statesChecked++;
            }
        }
        assertTrue(statesChecked > 10, "Trop peu de GAME_STATE vérifiés : " + statesChecked);
    }
}