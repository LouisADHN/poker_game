package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.controller.PlayerController;
import fr.louis.poker.engine.GameEngine;
import fr.louis.poker.event.GameEvent;
import fr.louis.poker.model.Player;
import fr.louis.poker.model.Table;
import fr.louis.poker.server.lobby.Seat;
import fr.louis.poker.server.lobby.TableSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Une partie en cours à une table.
 *
 * Elle possède son propre thread virtuel, qui joue les mains les unes après les autres.
 * Ce thread peut se permettre d'attendre les décisions des joueurs : il ne bloque personne d'autre.
 *
 * Diffusion des événements du moteur :
 * - HoleCardsDealt : envoyé UNIQUEMENT au joueur concerné, sur /user/queue/game
 * - tous les autres : envoyés à toute la table, sur /topic/tables/{id}/game
 */
public class GameSession {

    public static final String PRIVATE_QUEUE = "/queue/game";

    private static final Logger log = LoggerFactory.getLogger(GameSession.class);

    private final long tableId;
    private final SimpMessagingTemplate messaging;
    private final Duration pauseBetweenHands;
    private final Runnable onFinished;

    private final List<Player> players = new ArrayList<>();
    private final Map<Long, RemotePlayerController> controllersByUser = new HashMap<>();
    private final Map<String, Long> userIdByName = new HashMap<>();
    private final GameEngine engine;

    private volatile boolean running = true;
    private Thread thread;

    /**
     * @param onFinished appelé une seule fois quand la partie se termine, quelle qu'en soit la raison
     */
    public GameSession(long tableId,
                       List<Seat> seats,
                       TableSettings settings,
                       GameProperties properties,
                       SimpMessagingTemplate messaging,
                       Runnable onFinished) {
        this.tableId = tableId;
        this.messaging = messaging;
        this.pauseBetweenHands = properties.pauseBetweenHands();
        this.onFinished = onFinished;

        Table table = new Table(settings.smallBlind(), settings.bigBlind());
        Map<Player, PlayerController> controllers = new HashMap<>();

        for (Seat seat : seats) {
            // Le pseudo sert de nom dans le moteur : il est unique, la base le garantit
            Player player = new Player(seat.username(), settings.startingChips());
            table.addPlayer(player);
            players.add(player);

            RemotePlayerController controller = new RemotePlayerController(
                    seat.userId(),
                    message -> sendToUser(seat.userId(), message),
                    properties.turnTimeout());
            controllers.put(player, controller);
            controllersByUser.put(seat.userId(), controller);
            userIdByName.put(seat.username(), seat.userId());
        }

        // SecureRandom : un mélange imprévisible, impossible à deviner à partir des mains précédentes
        this.engine = new GameEngine(table, controllers, new SecureRandom());
        this.engine.addListener(this::onEvent);
    }

    public long getTableId() {
        return tableId;
    }

    public boolean isRunning() {
        return running;
    }

    /** Lance la partie dans un nouveau thread virtuel. */
    public void start() {
        thread = Thread.ofVirtual().name("table-" + tableId).start(this::run);
    }

    /** Arrête la partie : le thread est interrompu et se termine rapidement. */
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Transmet l'action d'un joueur à son contrôleur.
     *
     * @return false si le joueur n'est pas à cette table, ou si ce n'est pas son tour
     */
    public boolean submit(Long userId, Action action) {
        RemotePlayerController controller = controllersByUser.get(userId);
        return controller != null && controller.submit(action);
    }

    // ------------------------------------------------------------------
    // Boucle de jeu (thread de la table)

    private void run() {
        try {
            // Laisse aux navigateurs le temps de s'abonner aux messages de la partie
            Thread.sleep(pauseBetweenHands);

            while (running && !engine.isGameOver()) {
                engine.playHand();
                if (!engine.isGameOver()) {
                    Thread.sleep(pauseBetweenHands); // le temps de voir qui a gagné la main
                }
            }

            if (engine.isGameOver()) {
                sendToTable(gameOverMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Partie de la table {} arrêtée", tableId);
        } catch (RuntimeException e) {
            log.error("Erreur dans la partie de la table {}", tableId, e);
            sendToTable(new GameMessage("GAME_ERROR",
                    Map.of("message", "La partie s'est arrêtée à cause d'une erreur du serveur")));
        } finally {
            running = false;
            try {
                onFinished.run();
            } catch (RuntimeException e) {
                log.error("Erreur à la fermeture de la table {}", tableId, e);
            }
        }
    }

    private GameMessage gameOverMessage() {
        List<Map<String, Object>> standings = players.stream()
                .sorted(Comparator.comparingInt(Player::getChips).reversed())
                .map(p -> Map.<String, Object>of("player", p.getName(), "chips", p.getChips()))
                .toList();
        return new GameMessage("GAME_OVER", Map.of("standings", standings));
    }

    // ------------------------------------------------------------------
    // Diffusion

    private void onEvent(GameEvent event) {
        GameMessage message = GameMessages.fromEvent(event);

        if (event instanceof GameEvent.HoleCardsDealt(String player, var cards)) {
            // Les cartes privées ne partent qu'à leur propriétaire
            sendToUser(userIdByName.get(player), message);
        } else {
            sendToTable(message);
        }
    }

    private void sendToTable(GameMessage message) {
        messaging.convertAndSend(gameTopic(tableId), message);
    }

    private void sendToUser(Long userId, GameMessage message) {
        // Le "user" d'une connexion WebSocket est le subject du jeton : l'identifiant en texte
        messaging.convertAndSendToUser(String.valueOf(userId), PRIVATE_QUEUE, message);
    }

    public static String gameTopic(long tableId) {
        return "/topic/tables/" + tableId + "/game";
    }
}