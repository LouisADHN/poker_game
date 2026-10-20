package fr.louis.poker.server.lobby;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests du lobby autour d'un joueur qui quitte une partie en cours.
 * Test unitaire pur (ni Spring ni base) : les diffusions WebSocket sont simplement ignorées.
 */
class LobbyServiceTest {

    private static final Seat ALICE = new Seat(1L, "Alice");
    private static final Seat BOB = new Seat(2L, "Bob");

    private LobbyService lobby;

    @BeforeEach
    void setUp() {
        // Canal factice : accepte tous les messages sans rien en faire
        lobby = new LobbyService(new SimpMessagingTemplate((message, timeout) -> true));
    }

    private static CreateTableRequest request(String name) {
        return new CreateTableRequest(name, 10, 20, 200, 2);
    }

    /** Alice crée une table, Bob la rejoint, Alice lance la partie. */
    private TableView startedTable() {
        TableView table = lobby.create(ALICE, request("Table"));
        lobby.join(table.id(), BOB);
        return lobby.markPlaying(table.id(), ALICE.userId());
    }

    @Test
    @DisplayName("Pendant une partie, un joueur ne peut pas s'asseoir à une autre table")
    void playerIsSeatedDuringTheGame() {
        startedTable();
        assertThrows(LobbyConflictException.class, () -> lobby.create(BOB, request("Ailleurs")));
    }

    @Test
    @DisplayName("Un joueur libéré peut créer une autre table pendant que la partie continue")
    void releasedPlayerCanSitElsewhere() {
        TableView table = startedTable();

        lobby.releasePlayer(table.id(), BOB.userId());

        TableView other = assertDoesNotThrow(() -> lobby.create(BOB, request("Ailleurs")));
        assertEquals(BOB.userId(), other.ownerId());
        // Sa place à l'ancienne table reste : le moteur joue encore pour lui
        assertTrue(lobby.get(table.id()).players().contains(BOB));
    }

    @Test
    @DisplayName("La fin de l'ancienne partie ne libère pas le joueur de sa nouvelle table")
    void endOfOldGameKeepsNewTable() {
        TableView table = startedTable();
        lobby.releasePlayer(table.id(), BOB.userId());
        TableView other = lobby.create(BOB, request("Ailleurs"));

        lobby.endGame(table.id());

        // Bob est toujours assis à sa nouvelle table : il ne peut pas en créer une troisième
        assertThrows(LobbyConflictException.class, () -> lobby.create(BOB, request("Encore")));
        assertTrue(lobby.get(other.id()).players().contains(BOB));
        // Alice, elle, est bien libérée par la fin de partie
        assertDoesNotThrow(() -> lobby.create(ALICE, request("Revanche")));
    }

    @Test
    @DisplayName("Libérer un joueur au nom d'une autre table ne change rien")
    void releaseFromAnotherTableIsIgnored() {
        TableView table = startedTable();

        lobby.releasePlayer(table.id() + 100, BOB.userId());

        assertThrows(LobbyConflictException.class, () -> lobby.create(BOB, request("Ailleurs")));
    }
}