package fr.louis.poker.server.game;

import fr.louis.poker.server.lobby.Seat;
import fr.louis.poker.server.lobby.TableSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de la présence des joueurs dans une GameSession.
 *
 * La partie n'est jamais lancée (pas d'appel à start()) : aucun thread ne tourne,
 * on teste seulement la gestion des présences.
 */
class GameSessionPresenceTest {

    private static final Long LOUIS = 1L;
    private static final Long ALICE = 2L;
    private static final Long STRANGER = 99L;

    private GameSession session;

    /** Messages « envoyés » par la session, dans l'ordre : on les garde au lieu de les envoyer. */
    private List<Message<?>> sentMessages;

    @BeforeEach
    void setUp() {
        sentMessages = new ArrayList<>();
        session = newSession();
        // Comme après la pause de départ : les changements de présence sont diffusés
        session.announcePresences();
    }

    /** Une session à deux joueurs, pas encore lancée, dont les messages sont gardés dans sentMessages. */
    private GameSession newSession() {
        // Un faux canal de messages qui garde tout sans rien envoyer : pas besoin de vrai WebSocket ici
        SimpMessagingTemplate messaging = new SimpMessagingTemplate((message, timeout) -> sentMessages.add(message));

        return new GameSession(
                1L,
                "Table de test",
                List.of(new Seat(LOUIS, "Louis"), new Seat(ALICE, "Alice")),
                new TableSettings(10, 20, 1000, 6),
                new GameProperties(Duration.ofSeconds(30), Duration.ofSeconds(3), Duration.ofSeconds(30)),
                messaging,
                result -> { },
                () -> { });
    }

    @Test
    @DisplayName("Au lancement, tous les joueurs sont présents et jouent normalement")
    void everyoneIsPresentAtStart() {
        assertEquals(Presence.PRESENT, session.presenceOf(LOUIS));
        assertEquals(Presence.PRESENT, session.presenceOf(ALICE));
        assertFalse(session.controllerOf(LOUIS).isAutoPlay());
    }

    @Test
    @DisplayName("Un joueur déconnecté garde la main : pas encore de jeu automatique")
    void disconnectedPlayerIsNotAutoPlayed() {
        assertTrue(session.updatePresence(LOUIS, Presence.DISCONNECTED));

        assertEquals(Presence.DISCONNECTED, session.presenceOf(LOUIS));
        assertFalse(session.controllerOf(LOUIS).isAutoPlay());
    }

    @Test
    @DisplayName("Un joueur absent passe en jeu automatique, et le quitte à son retour")
    void awayPlayerIsAutoPlayedUntilHeComesBack() {
        session.updatePresence(LOUIS, Presence.AWAY);
        assertTrue(session.controllerOf(LOUIS).isAutoPlay());

        assertTrue(session.updatePresence(LOUIS, Presence.PRESENT));
        assertFalse(session.controllerOf(LOUIS).isAutoPlay());
    }

    @Test
    @DisplayName("Quitter la partie est définitif : une reconnexion ne fait pas revenir le joueur")
    void leftIsFinal() {
        assertTrue(session.updatePresence(LOUIS, Presence.LEFT));

        assertFalse(session.updatePresence(LOUIS, Presence.PRESENT));
        assertEquals(Presence.LEFT, session.presenceOf(LOUIS));
        assertTrue(session.controllerOf(LOUIS).isAutoPlay());
    }

    @Test
    @DisplayName("Rien ne change si le joueur a déjà cette présence")
    void samePresenceIsNotAChange() {
        assertFalse(session.updatePresence(LOUIS, Presence.PRESENT));
    }

    @Test
    @DisplayName("Un joueur qui n'est pas à la table est ignoré")
    void unknownPlayerIsIgnored() {
        assertFalse(session.updatePresence(STRANGER, Presence.AWAY));
        assertNull(session.presenceOf(STRANGER));
    }

    @Test
    @DisplayName("Changer la présence d'un joueur ne touche pas aux autres")
    void otherPlayersAreUnaffected() {
        session.updatePresence(LOUIS, Presence.AWAY);

        assertEquals(Presence.PRESENT, session.presenceOf(ALICE));
        assertFalse(session.controllerOf(ALICE).isAutoPlay());
    }

    // ------------------------------------------------------------------
    // Message PLAYER_PRESENCE

    @Test
    @DisplayName("Un changement de présence est annoncé à toute la table")
    void presenceChangeIsBroadcastToTheTable() {
        session.updatePresence(LOUIS, Presence.DISCONNECTED);

        assertEquals(1, sentMessages.size());
        Message<?> message = sentMessages.getFirst();
        assertEquals(GameSession.gameTopic(1L), SimpMessageHeaderAccessor.getDestination(message.getHeaders()));
        assertEquals(new GameMessage("PLAYER_PRESENCE", Map.of("player", "Louis", "presence", "DISCONNECTED")),
                message.getPayload());
    }

    @Test
    @DisplayName("Aucun message quand la présence ne change pas")
    void noMessageWithoutChange() {
        session.updatePresence(LOUIS, Presence.PRESENT);   // déjà présent
        session.updatePresence(STRANGER, Presence.AWAY);   // pas à la table
        assertTrue(sentMessages.isEmpty());

        session.updatePresence(LOUIS, Presence.LEFT);      // ce changement-là est annoncé…
        sentMessages.clear();
        session.updatePresence(LOUIS, Presence.PRESENT);   // …mais pas ce retour refusé
        assertTrue(sentMessages.isEmpty());
    }

    @Test
    @DisplayName("Les changements successifs arrivent dans l'ordre")
    void changesAreSentInOrder() {
        session.updatePresence(LOUIS, Presence.DISCONNECTED);
        session.updatePresence(LOUIS, Presence.AWAY);
        session.updatePresence(LOUIS, Presence.PRESENT);

        List<Object> presences = sentMessages.stream()
                .map(message -> ((GameMessage) message.getPayload()).data().get("presence"))
                .toList();
        assertEquals(List.of("DISCONNECTED", "AWAY", "PRESENT"), presences);
    }

    // ------------------------------------------------------------------
    // Pause de départ : les navigateurs ne sont pas encore abonnés

    @Test
    @DisplayName("Avant la pause de départ, un changement est noté sans être diffusé, puis annoncé")
    void presenceIsAnnouncedAfterTheStartPause() {
        GameSession fresh = newSession();

        fresh.updatePresence(LOUIS, Presence.DISCONNECTED);
        assertEquals(Presence.DISCONNECTED, fresh.presenceOf(LOUIS)); // noté…
        assertTrue(sentMessages.isEmpty());                            // …mais pas diffusé

        fresh.announcePresences();
        assertEquals(1, sentMessages.size());
        assertEquals(new GameMessage("PLAYER_PRESENCE", Map.of("player", "Louis", "presence", "DISCONNECTED")),
                sentMessages.getFirst().getPayload());

        // Ensuite, chaque changement est diffusé normalement
        fresh.updatePresence(LOUIS, Presence.AWAY);
        assertEquals(2, sentMessages.size());
    }

    @Test
    @DisplayName("Si tout le monde est là, l'annonce de départ n'envoie rien")
    void nothingToAnnounceWhenEveryoneIsPresent() {
        newSession().announcePresences();
        assertTrue(sentMessages.isEmpty());
    }
}