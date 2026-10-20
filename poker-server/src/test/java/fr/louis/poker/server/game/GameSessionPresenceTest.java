package fr.louis.poker.server.game;

import fr.louis.poker.server.lobby.Seat;
import fr.louis.poker.server.lobby.TableSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.List;

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

    @BeforeEach
    void setUp() {
        // Un faux canal de messages qui accepte tout sans rien envoyer : pas besoin de vrai WebSocket ici
        SimpMessagingTemplate messaging = new SimpMessagingTemplate((message, timeout) -> true);

        session = new GameSession(
                1L,
                "Table de test",
                List.of(new Seat(LOUIS, "Louis"), new Seat(ALICE, "Alice")),
                new TableSettings(10, 20, 1000, 6),
                new GameProperties(Duration.ofSeconds(30), Duration.ofSeconds(3)),
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
}