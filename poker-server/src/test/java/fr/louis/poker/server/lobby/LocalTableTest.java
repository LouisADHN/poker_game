package fr.louis.poker.server.lobby;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests des tables locales (mode écran partagé) dans le lobby.
 * Test unitaire pur (ni Spring ni base) : les diffusions WebSocket sont simplement ignorées.
 */
class LocalTableTest {

    /** L'hôte : le compte connecté sur l'écran partagé. Il n'est jamais assis. */
    private static final Long HOST = 1L;
    /** Un joueur avec un compte (identifiant positif) */
    private static final Seat LOUIS = new Seat(2L, "Louis");
    /** Deux invités (identifiants négatifs) */
    private static final Seat MAMIE = new Seat(-11L, "Mamie");
    private static final Seat TONTON = new Seat(-12L, "Tonton");

    private LobbyService lobby;

    @BeforeEach
    void setUp() {
        lobby = new LobbyService(new SimpMessagingTemplate((message, timeout) -> true));
    }

    private static CreateTableRequest request(String name) {
        return new CreateTableRequest(name, 10, 20, 1000, 6);
    }

    private LocalTable createLocal() {
        return lobby.createLocal(HOST, request("Soirée poker"));
    }

    // ------------------------------------------------------------------
    // Création

    @Test
    @DisplayName("Une table locale appartient à l'hôte, qui n'y est pas assis")
    void hostOwnsButDoesNotSit() {
        TableView table = createLocal().table();

        assertTrue(table.local());
        assertEquals(HOST, table.ownerId());
        assertTrue(table.players().isEmpty());
        assertEquals(TableStatus.WAITING, table.status());
    }

    @Test
    @DisplayName("Le code d'accès fait 6 caractères, sans caractère facile à confondre")
    void joinCodeFormat() {
        String code = createLocal().joinCode();

        assertEquals(LobbyService.JOIN_CODE_LENGTH, code.length());
        for (char c : code.toCharArray()) {
            assertTrue(LobbyService.JOIN_CODE_ALPHABET.indexOf(c) >= 0, "Caractère inattendu : " + c);
        }
        assertFalse(code.matches(".*[01OI].*"), "Le code contient un caractère ambigu : " + code);
    }

    @Test
    @DisplayName("Deux tables locales n'ont jamais le même code")
    void joinCodesAreUnique() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            codes.add(lobby.createLocal(HOST, request("Table " + i)).joinCode());
        }
        assertEquals(1000, codes.size());
    }

    @Test
    @DisplayName("Le code n'apparaît jamais dans la vue de la table, diffusée à tout le lobby")
    void joinCodeIsNeverInTheView() {
        LocalTable created = createLocal();

        assertFalse(created.table().toString().contains(created.joinCode()));
        assertFalse(lobby.get(created.table().id()).toString().contains(created.joinCode()));
    }

    @Test
    @DisplayName("Une table locale n'apparaît pas dans la liste du lobby, mais se consulte par son identifiant")
    void localTableIsHiddenFromTheLobby() {
        lobby.create(LOUIS, request("Table normale"));
        TableView local = createLocal().table();

        List<TableView> listed = lobby.list();
        assertEquals(1, listed.size());
        assertEquals("Table normale", listed.getFirst().name());
        assertEquals(local, lobby.get(local.id()));
    }

    // ------------------------------------------------------------------
    // Rejoindre

    @Test
    @DisplayName("Avec le code, un invité et un compte s'assoient à la table")
    void guestsAndAccountsJoinWithTheCode() {
        LocalTable created = createLocal();

        lobby.joinLocal(created.joinCode(), MAMIE);
        TableView table = lobby.joinLocal(created.joinCode(), LOUIS);

        assertEquals(List.of(MAMIE, LOUIS), table.players());
        assertEquals(HOST, table.ownerId(), "L'hôte reste propriétaire, même avec des joueurs assis");
    }

    @Test
    @DisplayName("Le code se saisit sans se soucier des minuscules ni des espaces")
    void codeIsForgiving() {
        LocalTable created = createLocal();

        lobby.joinLocal("  " + created.joinCode().toLowerCase() + " ", MAMIE);

        assertTrue(lobby.get(created.table().id()).players().contains(MAMIE));
    }

    @Test
    @DisplayName("Un code inconnu est refusé")
    void unknownCodeIsRefused() {
        createLocal();

        assertThrows(JoinCodeNotFoundException.class, () -> lobby.joinLocal("ZZZZZZ", MAMIE));
        assertThrows(JoinCodeNotFoundException.class, () -> lobby.joinLocal(null, MAMIE));
    }

    @Test
    @DisplayName("Une table locale ne se rejoint PAS par son identifiant, sans le code")
    void cannotJoinWithoutTheCode() {
        TableView table = createLocal().table();

        assertThrows(LobbyConflictException.class, () -> lobby.join(table.id(), LOUIS));
        assertTrue(lobby.get(table.id()).players().isEmpty());
    }

    @Test
    @DisplayName("Deux joueurs ne peuvent pas avoir le même pseudo à une table, quelle que soit la casse")
    void sameNameIsRefused() {
        LocalTable created = createLocal();
        lobby.joinLocal(created.joinCode(), MAMIE);

        assertThrows(LobbyConflictException.class,
                () -> lobby.joinLocal(created.joinCode(), new Seat(-99L, "mamie")));
    }

    @Test
    @DisplayName("Un joueur assis ailleurs ne peut pas rejoindre une table locale")
    void seatedElsewhereIsRefused() {
        lobby.create(LOUIS, request("Ailleurs"));
        LocalTable created = createLocal();

        assertThrows(LobbyConflictException.class, () -> lobby.joinLocal(created.joinCode(), LOUIS));
    }

    // ------------------------------------------------------------------
    // Lancement, départs, fin

    @Test
    @DisplayName("L'hôte lance la partie ; un joueur assis ne le peut pas")
    void onlyTheHostStarts() {
        LocalTable created = createLocal();
        lobby.joinLocal(created.joinCode(), MAMIE);
        lobby.joinLocal(created.joinCode(), TONTON);
        long id = created.table().id();

        assertThrows(LobbyConflictException.class, () -> lobby.markPlaying(id, MAMIE.userId()));
        assertEquals(TableStatus.PLAYING, lobby.markPlaying(id, HOST).status());
    }

    @Test
    @DisplayName("Une fois la partie lancée, le code ne permet plus de s'asseoir")
    void codeIsClosedOnceStarted() {
        LocalTable created = createLocal();
        lobby.joinLocal(created.joinCode(), MAMIE);
        lobby.joinLocal(created.joinCode(), TONTON);
        lobby.markPlaying(created.table().id(), HOST);

        assertThrows(LobbyConflictException.class, () -> lobby.joinLocal(created.joinCode(), LOUIS));
    }

    @Test
    @DisplayName("Le dernier joueur qui part laisse la table ouverte : l'écran attend toujours")
    void emptyLocalTableStaysOpen() {
        LocalTable created = createLocal();
        lobby.joinLocal(created.joinCode(), MAMIE);

        lobby.leave(created.table().id(), MAMIE.userId());

        TableView table = lobby.get(created.table().id());
        assertTrue(table.players().isEmpty());
        assertEquals(HOST, table.ownerId(), "La table ne passe à personne d'autre");
    }

    @Test
    @DisplayName("À la fin de la partie, la table disparaît et son code ne mène plus nulle part")
    void endOfGameClosesTheCode() {
        LocalTable created = createLocal();
        lobby.joinLocal(created.joinCode(), MAMIE);
        lobby.joinLocal(created.joinCode(), TONTON);
        lobby.markPlaying(created.table().id(), HOST);

        lobby.endGame(created.table().id());

        assertThrows(TableNotFoundException.class, () -> lobby.get(created.table().id()));
        assertThrows(JoinCodeNotFoundException.class, () -> lobby.joinLocal(created.joinCode(), LOUIS));
        // Les joueurs sont libérés : Mamie pourrait rejoindre une autre table
        LocalTable next = createLocal();
        assertDoesNotThrow(() -> lobby.joinLocal(next.joinCode(), MAMIE));
    }
}