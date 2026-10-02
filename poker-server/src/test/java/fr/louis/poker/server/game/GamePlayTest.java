package fr.louis.poker.server.game;

import com.jayway.jsonpath.JsonPath;
import fr.louis.poker.server.TestcontainersConfiguration;
import fr.louis.poker.server.lobby.CreateTableRequest;
import fr.louis.poker.server.lobby.LobbyService;
import fr.louis.poker.server.lobby.Seat;
import fr.louis.poker.server.lobby.TableView;
import fr.louis.poker.server.security.TokenService;
import fr.louis.poker.server.user.UserAccount;
import fr.louis.poker.server.user.UserRepository;
import fr.louis.poker.server.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test de bout en bout d'une partie : vrai serveur, vrais clients WebSocket.
 *
 * Alice crée une table de 2 joueurs, Bob la rejoint, Alice lance la partie.
 * En tête-à-tête, Alice (premier siège) a le bouton à la première main :
 * elle est small blind et parle en premier.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "poker.game.turn-timeout=1s",
                "poker.game.pause-between-hands=300ms"
        })
@Import(TestcontainersConfiguration.class)
class GamePlayTest {

    private static final int TIMEOUT_SECONDS = 10;

    @Value("${local.server.port}")
    private int port;

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenService tokenService;
    @Autowired private LobbyService lobbyService;
    @Autowired private GameService gameService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<WebSocketStompClient> stompClients = new ArrayList<>();

    private UserAccount aliceAccount;
    private UserAccount bobAccount;
    private long tableId;
    private TestPlayer alice;
    private TestPlayer bob;

    // ------------------------------------------------------------------
    // Un joueur de test : une connexion WebSocket, ses messages reçus, et une stratégie de jeu

    private class TestPlayer {
        final String name;
        final String token;
        StompSession session;
        final BlockingQueue<String> tableMessages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> privateMessages = new LinkedBlockingQueue<>();

        /** Reçoit le message YOUR_TURN (JSON) et renvoie l'action à jouer (JSON), ou null pour ne rien faire. */
        volatile Function<String, String> strategy;

        TestPlayer(String name, String token) {
            this.name = name;
            this.token = token;
        }

        void connect() throws Exception {
            WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
            stompClients.add(client);

            StompHeaders connectHeaders = new StompHeaders();
            connectHeaders.add("Authorization", "Bearer " + token);
            session = client.connectAsync("ws://localhost:" + port + "/ws",
                            new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
                            })
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            session.subscribe(GameSession.gameTopic(tableId), handler(tableMessages::add));
            session.subscribe("/user" + GameSession.PRIVATE_QUEUE, handler(json -> {
                privateMessages.add(json);
                if ("YOUR_TURN".equals(typeOf(json)) && strategy != null) {
                    String action = strategy.apply(json);
                    if (action != null) {
                        sendAction(action);
                    }
                }
            }));
        }

        void sendAction(String actionJson) {
            StompHeaders headers = new StompHeaders();
            headers.setDestination("/app/tables/" + tableId + "/action");
            headers.setContentType(MimeTypeUtils.APPLICATION_JSON);
            session.send(headers, actionJson.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static StompFrameHandler handler(java.util.function.Consumer<String> onMessage) {
        return new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                onMessage.accept(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        };
    }

    private static String typeOf(String json) {
        return JsonPath.read(json, "$.type");
    }

    /**
     * Lit les messages jusqu'à trouver le type demandé.
     * Tous les messages lus (y compris celui trouvé) sont ajoutés à "seen", dans l'ordre.
     */
    private static String waitFor(BlockingQueue<String> queue, String type, List<String> seen)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            String json = queue.poll(200, TimeUnit.MILLISECONDS);
            if (json != null) {
                seen.add(json);
                if (type.equals(typeOf(json))) {
                    return json;
                }
            }
        }
        fail("Message " + type + " non reçu à temps. Reçus : " + seen.stream().map(GamePlayTest::typeOf).toList());
        return null;
    }

    private HttpResponse<String> startGame(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/tables/" + tableId + "/start"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception {
        aliceAccount = userService.register("Alice", "motdepasse123");
        bobAccount = userService.register("Bob", "motdepasse123");

        // Tapis de 10 big blinds : les parties à tapis se terminent vite
        TableView table = lobbyService.create(
                new Seat(aliceAccount.getId(), "Alice"),
                new CreateTableRequest("Table de test", 10, 20, 200, 2));
        tableId = table.id();
        lobbyService.join(tableId, new Seat(bobAccount.getId(), "Bob"));

        alice = new TestPlayer("Alice", tokenService.issue(aliceAccount).value());
        bob = new TestPlayer("Bob", tokenService.issue(bobAccount).value());
        alice.connect();
        bob.connect();

        // Laisse au serveur le temps d'enregistrer les abonnements
        Thread.sleep(300);
    }

    @AfterEach
    void tearDown() throws Exception {
        gameService.stopAll();
        long deadline = System.currentTimeMillis() + 5000;
        while (gameService.isRunning(tableId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        // Si la partie n'a pas été lancée, on libère la table à la main
        quietly(() -> lobbyService.leave(tableId, bobAccount.getId()));
        quietly(() -> lobbyService.leave(tableId, aliceAccount.getId()));

        stompClients.forEach(WebSocketStompClient::stop);
        userRepository.deleteAll();
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // La table n'existe déjà plus : rien à faire
        }
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Seul le créateur peut lancer la partie")
    void onlyOwnerCanStart() throws Exception {
        assertEquals(409, startGame(bob.token).statusCode());

        HttpResponse<String> response = startGame(alice.token);
        assertEquals(200, response.statusCode());
        assertEquals("PLAYING", JsonPath.read(response.body(), "$.status"));
    }

    @Test
    @DisplayName("Une main complète : déroulement diffusé à la table, cartes envoyées en privé")
    void fullHandWithPrivateCards() throws Exception {
        alice.strategy = yourTurn -> "{\"type\": \"FOLD\"}";

        startGame(alice.token);

        // --- Déroulement public, vu par Alice
        List<String> tableSeen = new ArrayList<>();
        waitFor(alice.tableMessages, "HAND_ENDED", tableSeen);
        List<String> types = tableSeen.stream().map(GamePlayTest::typeOf).toList();

        assertEquals(List.of("HAND_STARTED", "BLIND_POSTED", "BLIND_POSTED",
                "PLAYER_ACTED", "POT_WON", "HAND_ENDED"), types);
        assertFalse(types.contains("HOLE_CARDS"), "Des cartes privées ont été diffusées à toute la table !");

        String folded = tableSeen.get(3);
        assertEquals("Alice", JsonPath.read(folded, "$.data.player"));
        assertEquals("FOLD", JsonPath.read(folded, "$.data.action.type"));

        String potWon = tableSeen.get(4);
        assertEquals("Bob", JsonPath.read(potWon, "$.data.player"));
        assertEquals(30, (int) JsonPath.read(potWon, "$.data.amount"));

        // --- Cartes privées : chacun ne reçoit que les siennes
        String aliceCards = waitFor(alice.privateMessages, "HOLE_CARDS", new ArrayList<>());
        assertEquals("Alice", JsonPath.read(aliceCards, "$.data.player"));
        assertEquals(2, (int) JsonPath.read(aliceCards, "$.data.cards.length()"));

        List<String> bobPrivate = new ArrayList<>();
        String bobCards = waitFor(bob.privateMessages, "HOLE_CARDS", bobPrivate);
        assertEquals("Bob", JsonPath.read(bobCards, "$.data.player"));
        bob.privateMessages.drainTo(bobPrivate);
        assertTrue(bobPrivate.stream()
                        .filter(json -> "HOLE_CARDS".equals(typeOf(json)))
                        .allMatch(json -> "Bob".equals(JsonPath.read(json, "$.data.player"))),
                "Bob a reçu des cartes qui ne sont pas les siennes !");
    }

    @Test
    @DisplayName("Une action hors de son tour renvoie une erreur privée")
    void actionOutsideTurnIsRejected() throws Exception {
        startGame(alice.token);

        // C'est à Alice de parler : Bob attend ses cartes, puis tente de jouer quand même
        waitFor(bob.privateMessages, "HOLE_CARDS", new ArrayList<>());
        bob.sendAction("{\"type\": \"CHECK\"}");

        String error = waitFor(bob.privateMessages, "ERROR", new ArrayList<>());
        assertEquals("Ce n'est pas ton tour", JsonPath.read(error, "$.data.message"));
    }

    @Test
    @DisplayName("Un joueur qui ne répond pas à temps est couché automatiquement")
    void timeoutFoldsPlayer() throws Exception {
        // Aucune stratégie : personne ne répond
        startGame(alice.token);

        String acted = waitFor(alice.tableMessages, "PLAYER_ACTED", new ArrayList<>());
        assertEquals("Alice", JsonPath.read(acted, "$.data.player"));
        assertEquals("FOLD", JsonPath.read(acted, "$.data.action.type")); // face à la big blind : fold
    }

    @Test
    @DisplayName("Une partie à tapis va jusqu'au bout, puis la table est fermée et les joueurs libérés")
    void gameRunsToTheEnd() throws Exception {
        alice.strategy = yourTurn -> "{\"type\": \"ALL_IN\"}";
        bob.strategy = yourTurn -> "{\"type\": \"ALL_IN\"}";

        startGame(alice.token);

        String gameOver = waitFor(alice.tableMessages, "GAME_OVER", new ArrayList<>());
        List<Integer> chips = JsonPath.read(gameOver, "$.data.standings[*].chips");
        assertEquals(List.of(400, 0), chips); // le gagnant a tout, le total est conservé

        // La partie se termine, puis la table disparaît du lobby
        long deadline = System.currentTimeMillis() + 5000;
        while (gameService.isRunning(tableId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(gameService.isRunning(tableId));
        assertTrue(lobbyService.list().stream().noneMatch(t -> t.id() == tableId));

        // Alice est de nouveau libre : elle peut créer une nouvelle table
        TableView newTable = lobbyService.create(new Seat(aliceAccount.getId(), "Alice"),
                new CreateTableRequest("Revanche", 10, 20, 200, 2));
        lobbyService.leave(newTable.id(), aliceAccount.getId());
    }
}