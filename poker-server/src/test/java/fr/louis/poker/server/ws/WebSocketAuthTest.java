package fr.louis.poker.server.ws;

import com.jayway.jsonpath.JsonPath;
import fr.louis.poker.server.TestcontainersConfiguration;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de la connexion WebSocket : un vrai serveur est démarré sur un port aléatoire,
 * et un vrai client STOMP s'y connecte.
 * Pas de @Transactional ici : le serveur tourne dans d'autres threads,
 * donc on nettoie la base à la main après chaque test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WebSocketAuthTest {

    private static final int TIMEOUT_SECONDS = 5;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TokenService tokenService;

    private WebSocketStompClient stompClient;
    private UserAccount louis;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        louis = userService.register("Louis", "motdepasse123");
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
        userRepository.deleteAll();
    }

    /** Ouvre une session STOMP, avec un jeton dans la trame CONNECT si token n'est pas null. */
    private StompSession connect(String token) throws Exception {
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        return stompClient.connectAsync(
                        "ws://localhost:" + port + "/ws",
                        new WebSocketHttpHeaders(),
                        connectHeaders,
                        new StompSessionHandlerAdapter() {
                        })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** S'abonne à une destination et renvoie la file où arriveront les messages reçus (en JSON brut). */
    private BlockingQueue<String> subscribe(StompSession session, String destination) {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
        return received;
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Avec un jeton valide, la connexion est acceptée et attribuée au bon joueur")
    void validTokenIsAccepted() throws Exception {
        String token = tokenService.issue(louis).value();
        StompSession session = connect(token);
        assertTrue(session.isConnected());

        BlockingQueue<String> replies = subscribe(session, "/app/me");
        String reply = replies.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertNotNull(reply, "Aucune réponse reçue du serveur");
        assertEquals("Louis", JsonPath.read(reply, "$.username"));
        assertEquals(louis.getId().intValue(), (int) JsonPath.read(reply, "$.id"));
    }

    @Test
    @DisplayName("Sans jeton, la connexion est refusée")
    void missingTokenIsRejected() {
        // Échec de la connexion : erreur reçue ou délai dépassé
        assertThrows(Exception.class, () -> connect(null));
    }

    @Test
    @DisplayName("Avec un jeton invalide, la connexion est refusée")
    void invalidTokenIsRejected() {
        assertThrows(Exception.class, () -> connect("ceci.nest.pasunjeton"));
    }
}