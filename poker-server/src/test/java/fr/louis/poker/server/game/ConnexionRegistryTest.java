package fr.louis.poker.server.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du ConnectionRegistry, sans Spring : on appelle directement connected() et disconnected(),
 * et un faux presenceUpdater note les changements de présence demandés.
 */
class ConnectionRegistryTest {

    private static final Long LOUIS = 1L;

    /** Délai de grâce court, pour des tests rapides. */
    private static final Duration GRACE = Duration.ofMillis(300);

    /** Changements de présence demandés, sous la forme "1:AWAY". Rempli par deux threads : file thread-safe. */
    private BlockingQueue<String> updates;
    private ConnectionRegistry registry;

    @BeforeEach
    void setUp() {
        updates = new LinkedBlockingQueue<>();
        registry = new ConnectionRegistry((userId, presence) -> updates.add(userId + ":" + presence), GRACE);
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    /** Attend le prochain changement de présence (au plus 2 s). */
    private String nextUpdate() throws InterruptedException {
        return updates.poll(2, TimeUnit.SECONDS);
    }

    /** Vérifie qu'aucun changement n'arrive pendant le temps donné. */
    private void assertNoUpdateFor(Duration duration) throws InterruptedException {
        String update = updates.poll(duration.toMillis(), TimeUnit.MILLISECONDS);
        assertNull(update, "Changement de présence inattendu : " + update);
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("À sa première connexion, le joueur est présent")
    void firstConnectionMeansPresent() throws Exception {
        registry.connected(LOUIS, "s1");

        assertEquals("1:PRESENT", nextUpdate());
        assertTrue(registry.isConnected(LOUIS));
    }

    @Test
    @DisplayName("Dernière connexion fermée : déconnecté, puis absent après le délai de grâce")
    void disconnectedThenAwayAfterGrace() throws Exception {
        registry.connected(LOUIS, "s1");
        nextUpdate();

        long closedAt = System.currentTimeMillis();
        registry.disconnected(LOUIS, "s1");

        assertEquals("1:DISCONNECTED", nextUpdate());
        assertFalse(registry.isConnected(LOUIS));
        assertEquals("1:AWAY", nextUpdate());
        assertTrue(System.currentTimeMillis() - closedAt >= GRACE.toMillis(),
                "Le joueur est passé absent avant la fin du délai de grâce");
    }

    @Test
    @DisplayName("Reconnexion pendant le délai de grâce : présent, et jamais absent")
    void reconnectionCancelsGrace() throws Exception {
        registry.connected(LOUIS, "s1");
        nextUpdate();
        registry.disconnected(LOUIS, "s1");
        nextUpdate();

        registry.connected(LOUIS, "s2");

        assertEquals("1:PRESENT", nextUpdate());
        assertNoUpdateFor(GRACE.multipliedBy(2)); // le délai annulé ne doit rien faire
    }

    @Test
    @DisplayName("Deux onglets : en fermer un ne change rien, fermer les deux déconnecte")
    void twoTabs() throws Exception {
        registry.connected(LOUIS, "s1");
        registry.connected(LOUIS, "s2");
        assertEquals("1:PRESENT", nextUpdate()); // un seul PRESENT pour les deux onglets

        registry.disconnected(LOUIS, "s1");
        assertNoUpdateFor(Duration.ofMillis(100));
        assertTrue(registry.isConnected(LOUIS));

        registry.disconnected(LOUIS, "s2");
        assertEquals("1:DISCONNECTED", nextUpdate());
    }

    @Test
    @DisplayName("Rechargement de page : la nouvelle connexion arrive avant la fermeture de l'ancienne")
    void reloadWithOverlappingConnections() throws Exception {
        registry.connected(LOUIS, "s1");
        nextUpdate();

        registry.connected(LOUIS, "s2");     // la page rechargée se connecte…
        registry.disconnected(LOUIS, "s1");  // …puis le serveur remarque la fermeture de l'ancienne

        assertNoUpdateFor(GRACE.multipliedBy(2)); // le joueur n'a jamais cessé d'être connecté
    }

    @Test
    @DisplayName("Une déconnexion annoncée deux fois n'est comptée qu'une fois")
    void duplicateDisconnectIsIgnored() throws Exception {
        registry.connected(LOUIS, "s1");
        registry.connected(LOUIS, "s2");
        nextUpdate();

        registry.disconnected(LOUIS, "s1");
        registry.disconnected(LOUIS, "s1"); // doublon : ne doit pas faire croire que s2 est fermée

        assertNoUpdateFor(Duration.ofMillis(100));
        assertTrue(registry.isConnected(LOUIS));
    }

    @Test
    @DisplayName("Parti, revenu, reparti : seul le dernier délai de grâce compte")
    void onlyTheLatestGraceCounts() throws Exception {
        registry.connected(LOUIS, "s1");
        nextUpdate();

        registry.disconnected(LOUIS, "s1");  // premier délai : finirait à t + 300 ms
        Thread.sleep(150);
        registry.connected(LOUIS, "s2");
        long secondClose = System.currentTimeMillis();
        registry.disconnected(LOUIS, "s2");  // second délai : finit à t + 450 ms

        List<String> received = new ArrayList<>();
        String update;
        while (!"1:AWAY".equals(update = nextUpdate())) {
            assertNotNull(update, "Le joueur n'est jamais passé absent");
            received.add(update);
        }

        assertEquals(List.of("1:DISCONNECTED", "1:PRESENT", "1:DISCONNECTED"), received);
        // Si le premier délai avait agi, AWAY serait arrivé ~150 ms après la seconde fermeture
        assertTrue(System.currentTimeMillis() - secondClose >= GRACE.toMillis(),
                "Un ancien délai de grâce a fait passer le joueur absent trop tôt");
        assertNoUpdateFor(GRACE); // et un seul AWAY
    }
}