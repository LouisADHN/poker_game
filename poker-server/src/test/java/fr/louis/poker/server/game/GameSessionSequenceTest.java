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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests des numéros de séquence et de la photographie publique d'une GameSession.
 *
 * La partie tourne vraiment : personne ne répond, chaque tour se termine au bout d'un délai
 * très court (check ou couché automatique), les mains s'enchaînent vite.
 */
class GameSessionSequenceTest {

    private static final Long LOUIS = 1L;
    private static final Long ALICE = 2L;
    private static final int STARTING_CHIPS = 1000;

    /** Messages envoyés par la session. Le thread de la table y ajoute pendant que le test les lit. */
    private List<Message<?>> sentMessages;
    private CountDownLatch finished;
    private GameSession session;

    @BeforeEach
    void setUp() {
        sentMessages = new CopyOnWriteArrayList<>();
        finished = new CountDownLatch(1);
        SimpMessagingTemplate messaging = new SimpMessagingTemplate((message, timeout) -> sentMessages.add(message));
        session = new GameSession(
                1L,
                "Table de test",
                List.of(new Seat(LOUIS, "Louis"), new Seat(ALICE, "Alice")),
                new TableSettings(10, 20, STARTING_CHIPS, 6),
                // 30 ms pour jouer, 10 ms entre deux mains : des mains en rafale
                new GameProperties(Duration.ofMillis(30), Duration.ofMillis(10), Duration.ofSeconds(30)),
                messaging,
                result -> { },
                finished::countDown);
    }

    @AfterEach
    void tearDown() {
        session.stop();
    }

    // ------------------------------------------------------------------

    private static String destination(Message<?> message) {
        return SimpMessageHeaderAccessor.getDestination(message.getHeaders());
    }

    /** Le numéro de séquence d'un message, ou null s'il n'en a pas. */
    private static Long seqOf(Message<?> message) {
        String seq = SimpMessageHeaderAccessor.wrap(message).getFirstNativeHeader(GameSession.SEQ_HEADER);
        return seq == null ? null : Long.valueOf(seq);
    }

    private List<Message<?>> tableMessages() {
        return sentMessages.stream()
                .filter(message -> GameSession.gameTopic(1L).equals(destination(message)))
                .toList();
    }

    private long handsEnded() {
        return tableMessages().stream()
                .filter(message -> ((GameMessage) message.getPayload()).type().equals("HAND_ENDED"))
                .count();
    }

    /** Lance la partie, laisse jouer quelques mains, puis l'arrête et attend la fin de son thread. */
    private void playSomeHands(int hands) throws InterruptedException {
        session.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (handsEnded() < hands && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        session.stop();
        assertTrue(finished.await(5, TimeUnit.SECONDS), "Le thread de la table ne s'est pas arrêté");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Avant tout message, la photographie porte le numéro 0")
    void nothingSentYet() {
        GameSession.PublicView view = session.publicView();

        assertEquals(0, view.seq());
        assertEquals(0, view.table().handNumber());
    }

    @Test
    @DisplayName("Les messages publics sont numérotés 1, 2, 3… sans trou ni doublon")
    void tableMessagesAreNumberedInOrder() throws Exception {
        playSomeHands(3);

        List<Long> numbers = tableMessages().stream().map(GameSessionSequenceTest::seqOf).toList();
        List<Long> expected = new ArrayList<>();
        for (long i = 1; i <= numbers.size(); i++) {
            expected.add(i);
        }
        assertEquals(expected, numbers);
    }

    @Test
    @DisplayName("Les messages privés n'ont pas de numéro : ils ne font pas partie de la suite publique")
    void privateMessagesHaveNoNumber() throws Exception {
        playSomeHands(1);

        List<Message<?>> privateMessages = sentMessages.stream()
                .filter(message -> !GameSession.gameTopic(1L).equals(destination(message)))
                .toList();
        assertFalse(privateMessages.isEmpty(), "Aucun message privé envoyé (HOLE_CARDS, YOUR_TURN)");
        privateMessages.forEach(message -> assertNull(seqOf(message)));
    }

    @Test
    @DisplayName("Les changements de présence sont numérotés comme les autres messages publics")
    void presenceMessagesAreNumbered() {
        session.announcePresences();
        session.updatePresence(LOUIS, Presence.DISCONNECTED);
        session.updatePresence(LOUIS, Presence.PRESENT);

        assertEquals(List.of(1L, 2L), tableMessages().stream().map(GameSessionSequenceTest::seqOf).toList());
        assertEquals(2, session.publicView().seq());
    }

    @Test
    @DisplayName("Après la partie, la photographie porte le numéro du dernier message et suit le jeu")
    void viewMatchesLastMessage() throws Exception {
        playSomeHands(3);

        GameSession.PublicView view = session.publicView();
        assertEquals(tableMessages().size(), view.seq());
        assertTrue(view.table().handNumber() >= 3);

        int total = view.table().pot();
        for (TableSnapshot.PlayerSnapshot player : view.table().players()) {
            total += player.chips();
        }
        assertEquals(2 * STARTING_CHIPS, total, "Des jetons sont apparus ou ont disparu");
    }

    @Test
    @DisplayName("Une photographie prise pendant la partie correspond exactement aux messages qu'elle annonce")
    void viewsTakenDuringTheGameAreConsistent() throws Exception {
        // Un autre thread prend des photographies en continu pendant que la partie tourne
        List<GameSession.PublicView> views = new CopyOnWriteArrayList<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            while (finished.getCount() > 0) {
                views.add(session.publicView());
                LockSupport.parkNanos(200_000); // 0,2 ms : quelques milliers de photographies, pas des millions
            }
        });

        playSomeHands(5);
        reader.join(5000);

        // Pour chaque photographie : le nombre de HAND_STARTED parmi les messages 1..seq
        // doit être exactement son numéro de main. Un décalage d'un seul message serait visible ici.
        List<Message<?>> messages = tableMessages();
        assertFalse(views.isEmpty());
        for (GameSession.PublicView view : views) {
            long handsStarted = messages.stream()
                    .filter(message -> seqOf(message) <= view.seq())
                    .filter(message -> ((GameMessage) message.getPayload()).type().equals("HAND_STARTED"))
                    .count();
            assertEquals(handsStarted, view.table().handNumber(),
                    "Photographie incohérente au numéro " + view.seq());
        }
    }
}