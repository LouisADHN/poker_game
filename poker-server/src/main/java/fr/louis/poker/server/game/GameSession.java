package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.controller.PlayerController;
import fr.louis.poker.engine.GameEngine;
import fr.louis.poker.event.GameEvent;
import fr.louis.poker.model.Player;
import fr.louis.poker.model.Table;
import fr.louis.poker.server.lobby.Seat;
import fr.louis.poker.server.lobby.TableSettings;
import fr.louis.poker.server.stats.GameResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Une partie en cours à une table.
 *
 * Elle possède son propre thread virtuel, qui joue les mains les unes après les autres.
 * Ce thread peut se permettre d'attendre les décisions des joueurs : il ne bloque personne d'autre.
 *
 * Diffusion des événements du moteur :
 * - HoleCardsDealt : envoyé UNIQUEMENT au joueur concerné, sur /user/queue/game
 * - tous les autres : envoyés à toute la table, sur /topic/tables/{id}/game
 *
 * Un ResultTracker observe aussi la partie : quand elle se termine normalement,
 * son résultat est transmis à onCompleted pour être enregistré.
 */
public class GameSession {

    public static final String PRIVATE_QUEUE = "/queue/game";

    /** En-tête STOMP qui porte le numéro de séquence de chaque message public de la table. */
    public static final String SEQ_HEADER = "seq";

    /**
     * L'état public de la table, avec le numéro du dernier message public qu'il inclut.
     * Un client qui reçoit cette photographie sait ainsi quels messages il a déjà « vus ».
     */
    public record PublicView(long seq, TableSnapshot table) {
    }

    /** Pourquoi la partie s'est terminée : envoyé dans le message GAME_OVER. */
    public enum EndReason {
        /** Il ne reste qu'un joueur avec des jetons : la partie est allée à son terme. */
        COMPLETED,
        /** Trop de joueurs sont partis ou absents pour continuer. La partie ne compte pas au classement. */
        ABANDONED
    }

    private static final Logger log = LoggerFactory.getLogger(GameSession.class);

    private final long tableId;
    private final String tableName;
    private final SimpMessagingTemplate messaging;
    private final Duration pauseBetweenHands;
    private final Consumer<GameResult> onCompleted;
    private final Runnable onFinished;

    private final List<Player> players = new ArrayList<>();
    private final Map<Long, RemotePlayerController> controllersByUser = new HashMap<>();
    private final Map<String, Long> userIdByName = new HashMap<>();
    private final Map<Long, String> nameByUserId = new HashMap<>();
    /**
     * Présence de chaque joueur, par identifiant.
     * Modifiée par plusieurs threads (WebSocket, minuteur, HTTP) : toujours lue et écrite
     * dans une méthode synchronized de cette classe.
     */

    private final Map<Long, Presence> presenceByUser = new HashMap<>();
    private final GameEngine engine;
    private final ResultTracker resultTracker;

    /** L'état lisible de la partie, mis à jour à chaque événement du moteur. */
    private final TableState tableState;

    /**
     * Numéro du dernier message public envoyé à la table (0 : aucun).
     * Incrémenté sous le verrou de la session, en même temps que l'envoi : voir sendToTable().
     */
    private long seq = 0;

    /**
     * Faux tant que les navigateurs n'ont pas eu le temps de s'abonner à la partie.
     * Pendant ce temps, les changements de présence sont notés mais pas diffusés (ils seraient perdus) ;
     * announcePresences() annonce ensuite l'état de chacun. Protégé par le verrou de la session.
     */
    private boolean presenceBroadcast = false;
    private volatile boolean running = true;
    private Thread thread;
    private Instant startedAt;

    /**
     * @param onCompleted appelé avec le résultat, uniquement si la partie va jusqu'à son terme
     * @param onFinished  appelé une seule fois quand la partie se termine, quelle qu'en soit la raison
     */
    public GameSession(long tableId,
                       String tableName,
                       List<Seat> seats,
                       TableSettings settings,
                       GameProperties properties,
                       SimpMessagingTemplate messaging,
                       Consumer<GameResult> onCompleted,
                       Runnable onFinished) {
        this.tableId = tableId;
        this.tableName = tableName;
        this.messaging = messaging;
        this.pauseBetweenHands = properties.pauseBetweenHands();
        this.onCompleted = onCompleted;
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
            nameByUserId.put(seat.userId(), seat.username());
            presenceByUser.put(seat.userId(), Presence.PRESENT);
        }

        // SecureRandom : un mélange imprévisible, impossible à deviner à partir des mains précédentes
        this.engine = new GameEngine(table, controllers, new SecureRandom());

        // Créée AVANT l'ajout du listener : onEvent s'en sert dès le premier événement
        this.tableState = new TableState(
                seats.stream().map(Seat::username).toList(),
                settings.startingChips());

        this.engine.addListener(this::onEvent);

        // Second listener : il tient les comptes pour le résultat final
        this.resultTracker = new ResultTracker(players, userIdByName);
        this.engine.addListener(resultTracker);
    }

    public long getTableId() {
        return tableId;
    }

    public boolean isRunning() {
        return running;
    }

    /** Lance la partie dans un nouveau thread virtuel. */
    public void start() {
        startedAt = Instant.now();
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
    // Présence des joueurs

    /**
     * Change la présence d'un joueur, et active ou coupe son jeu automatique en conséquence.
     * Appelé par d'autres threads que celui de la table.
     *
     * LEFT est définitif : une fois parti, le joueur ne peut plus changer d'état.
     *
     * @return true si la présence a changé ; false si le joueur n'est pas à cette table,
     *         s'il avait déjà cette présence, ou s'il a quitté la partie
     */
    public synchronized boolean updatePresence(Long userId, Presence presence) {
        Presence current = presenceByUser.get(userId);
        if (current == null || current == presence || current == Presence.LEFT) {
            return false;
        }

        presenceByUser.put(userId, presence);
        controllersByUser.get(userId).setAutoPlay(presence.isAutoPlay());

        // Envoyé sous le verrou : la table reçoit les changements dans l'ordre où ils ont eu lieu
        if (presenceBroadcast) {
            sendToTable(GameMessages.playerPresence(nameByUserId.get(userId), presence));
        }
        return true;
    }

    /**
     * Les navigateurs sont abonnés : annonce les joueurs qui ne sont pas présents,
     * puis diffuse désormais chaque changement. Appelé par le thread de la table, après la pause de départ.
     */
    synchronized void announcePresences() {
        presenceBroadcast = true;
        presenceByUser.forEach((userId, presence) -> {
            if (presence != Presence.PRESENT) {
                sendToTable(GameMessages.playerPresence(nameByUserId.get(userId), presence));
            }
        });
    }

    /** @return la présence du joueur, ou null s'il n'est pas à cette table */
    public synchronized Presence presenceOf(Long userId) {
        return presenceByUser.get(userId);
    }

    /**
     * Vrai si la partie ne peut plus continuer, faute de joueurs.
     * Appelé par le thread de la table, entre deux mains.
     *
     * Seuls comptent les joueurs qui ont encore des jetons (un joueur éliminé ne joue plus).
     * La partie est abandonnée :
     * - s'il en reste moins de 2 qui n'ont pas quitté la partie ;
     * - ou si aucun d'eux n'est connecté (ou en train de se reconnecter) :
     *   sinon des joueurs en automatique s'échangeraient les blinds sans fin.
     */
    synchronized boolean isAbandoned() {
        int notLeft = 0;
        int connected = 0;
        for (Player player : players) {
            if (player.getChips() == 0) {
                continue;
            }
            Presence presence = presenceByUser.get(userIdByName.get(player.getName()));
            if (presence != Presence.LEFT) {
                notLeft++;
            }
            if (presence == Presence.PRESENT || presence == Presence.DISCONNECTED) {
                connected++;
            }
        }
        return notLeft < 2 || connected == 0;
    }

    /** Réservé aux tests : le contrôleur d'un joueur. */
    RemotePlayerController controllerOf(Long userId) {
        return controllersByUser.get(userId);
    }

    // ------------------------------------------------------------------
    // Boucle de jeu (thread de la table)

    private void run() {
        try {
            // Laisse aux navigateurs le temps de s'abonner aux messages de la partie
            Thread.sleep(pauseBetweenHands);
            announcePresences();

            boolean abandoned = false;
            while (running && !engine.isGameOver()) {
                // Vérifié ENTRE deux mains : interrompre une main laisserait des jetons au milieu du pot
                if (isAbandoned()) {
                    abandoned = true;
                    break;
                }
                engine.playHand();
                if (!engine.isGameOver()) {
                    Thread.sleep(pauseBetweenHands); // le temps de voir qui a gagné la main
                }
            }

            if (abandoned) {
                log.info("Partie de la table {} abandonnée", tableId);
                sendToTable(gameOverMessage(EndReason.ABANDONED)); // pas de recordResult() : elle ne compte pas
            } else if (engine.isGameOver()) {
                sendToTable(gameOverMessage(EndReason.COMPLETED));
                recordResult();
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

    /** Transmet le résultat ; une erreur d'enregistrement ne doit pas empêcher la fermeture de la table. */
    private void recordResult() {
        try {
            onCompleted.accept(resultTracker.toResult(tableName, startedAt));
        } catch (RuntimeException e) {
            log.error("Impossible d'enregistrer le résultat de la table {}", tableId, e);
        }
    }

    private GameMessage gameOverMessage(EndReason reason) {
        List<Map<String, Object>> standings = players.stream()
                .sorted(Comparator.comparingInt(Player::getChips).reversed())
                .map(p -> Map.<String, Object>of("player", p.getName(), "chips", p.getChips()))
                .toList();
        return new GameMessage("GAME_OVER", Map.of(
                "reason", reason.name(),
                "standings", standings));
    }

    // ------------------------------------------------------------------
    // Diffusion

    /**
     * Appelé par le thread de la table pour chaque événement du moteur.
     *
     * synchronized : mettre à jour la projection et envoyer le message forment un seul bloc.
     * Un autre thread qui demande publicView() voit donc l'état AVANT l'événement (et l'ancien numéro),
     * ou APRÈS (et le nouveau numéro), jamais un mélange des deux.
     *
     * Le verrou n'est pas gardé pendant que le moteur attend la décision d'un joueur :
     * decide() est appelé par le moteur en dehors des listeners.
     */
    private synchronized void onEvent(GameEvent event) {
        tableState.onEvent(event);
        GameMessage message = GameMessages.fromEvent(event);

        if (event instanceof GameEvent.HoleCardsDealt(String player, var cards)) {
            // Les cartes privées ne partent qu'à leur propriétaire
            sendToUser(userIdByName.get(player), message);
        } else {
            sendToTable(message);
        }
    }

    /**
     * Envoie un message à toute la table, avec le numéro de séquence suivant dans l'en-tête "seq".
     * synchronized : deux messages ne peuvent pas recevoir le même numéro, et ils partent
     * dans l'ordre de leurs numéros (le broker conserve ensuite l'ordre d'envoi).
     */
    private synchronized void sendToTable(GameMessage message) {
        seq++;
        messaging.convertAndSend(gameTopic(tableId), message, Map.of(SEQ_HEADER, seq));
    }

    /**
     * Envoie à un joueur l'état complet de la partie, en message privé GAME_STATE :
     * la photographie publique et son numéro, la présence de chacun, ses cartes et son tour en attente.
     *
     * Tout est lu ET envoyé sous le verrou de la session. Aucun autre message de la partie ne peut donc
     * partir entre la photographie et l'envoi : pour ce joueur, tout ce qui arrive avant GAME_STATE
     * y est déjà inclus, tout ce qui arrive après est plus récent.
     *
     * @return false si le joueur n'est pas à cette table
     */
    synchronized boolean sendStateTo(Long userId) {
        RemotePlayerController controller = controllersByUser.get(userId);
        if (controller == null) {
            return false;
        }

        Map<String, String> presences = new LinkedHashMap<>();
        presenceByUser.forEach((id, presence) -> presences.put(nameByUserId.get(id), presence.name()));

        // LinkedHashMap et pas Map.of : "yourTurn" peut valoir null, ce que Map.of refuse
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("seq", seq);
        data.put("table", tableState.snapshot());
        data.put("presences", presences);
        data.put("holeCards", tableState.holeCardsOf(nameByUserId.get(userId)));
        data.put("yourTurn", controller.pendingTurn().map(GameMessage::data).orElse(null));

        sendToUser(userId, new GameMessage("GAME_STATE", data));
        return true;
    }

    /** L'état public de la table et le numéro du dernier message public qu'il inclut, lus ensemble. */
    synchronized PublicView publicView() {
        return new PublicView(seq, tableState.snapshot());
    }

    /**
     * Envoie un message privé à un joueur.
     *
     * synchronized, comme sendToTable : TOUS les messages de la partie (publics et privés,
     * GAME_STATE compris) partent ainsi l'un après l'autre, dans un ordre unique,
     * que le broker conserve jusqu'au navigateur.
     */
    private synchronized void sendToUser(Long userId, GameMessage message) {
        // Le "user" d'une connexion WebSocket est le subject du jeton : l'identifiant en texte
        messaging.convertAndSendToUser(String.valueOf(userId), PRIVATE_QUEUE, message);
    }

    public static String gameTopic(long tableId) {
        return "/topic/tables/" + tableId + "/game";
    }
}