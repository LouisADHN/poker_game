package fr.louis.poker.server.lobby;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.*;

/**
 * Gère les tables du lobby, en mémoire.
 *
 * Concurrence : toutes les méthodes publiques sont synchronized.
 * Un seul thread à la fois modifie le lobby, ce qui garantit les règles
 * même quand plusieurs joueurs agissent au même instant
 * (deux joueurs ne peuvent pas prendre la dernière place, un joueur ne peut pas
 * s'asseoir à deux tables).
 *
 * Chaque changement est diffusé en WebSocket :
 * - /topic/lobby       : la liste complète des tables
 * - /topic/tables/{id} : le détail de la table modifiée
 */
@Service
public class LobbyService {

    public static final String LOBBY_TOPIC = "/topic/lobby";
    public static final String TABLE_TOPIC_PREFIX = "/topic/tables/";


    static final String JOIN_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int JOIN_CODE_LENGTH = 6;

    /** LinkedHashMap : les tables sont listées dans l'ordre de création. */
    private final Map<Long, LobbyTable> tables = new LinkedHashMap<>();

    /** Table où chaque joueur est assis : userId -> tableId. */
    private final Map<Long, Long> tableByUser = new HashMap<>();

    /** Table locale de chaque code d'accès : code -> tableId. */
    private final Map<String, Long> tableByCode = new HashMap<>();

    /** SecureRandom : un code impossible à deviner à partir des codes précédents. */
    private final SecureRandom random = new SecureRandom();

    private final SimpMessagingTemplate messaging;
    private long nextId = 1;

    public LobbyService(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    public synchronized TableView create(Seat creator, CreateTableRequest request) {
        ensureNotSeatedElsewhere(creator.userId());

        // Peut lever une IllegalArgumentException (paramètres incohérents) : rien n'est créé
        TableSettings settings = request.toSettings();

        LobbyTable table = new LobbyTable(nextId++, request.name().trim(), settings, creator);
        tables.put(table.id(), table);
        tableByUser.put(creator.userId(), table.id());

        publish(table);
        return table.toView();
    }

    /** Les tables du lobby. Les tables locales n'y sont pas : elles se rejoignent avec leur code. */
    public synchronized List<TableView> list() {
        return tables.values().stream()
                .filter(table -> !table.isLocal())
                .map(LobbyTable::toView)
                .toList();
    }

    /**
     * Crée une table locale. L'hôte (le compte connecté sur l'écran partagé) la possède
     * et pourra lancer la partie, mais ne s'y assoit pas : il n'est pas un joueur.
     *
     * @return la table et son code d'accès, que seul l'hôte reçoit
     */
    public synchronized LocalTable createLocal(Long hostId, CreateTableRequest request) {
        TableSettings settings = request.toSettings();
        String code = newJoinCode();

        LobbyTable table = LobbyTable.local(nextId++, request.name().trim(), settings, hostId, code);
        tables.put(table.id(), table);
        tableByCode.put(code, table.id());

        publish(table);
        return new LocalTable(table.toView(), code);
    }

    /**
     * S'assoit à une table locale grâce à son code. Valable pour un invité comme pour un compte.
     * Le code se saisit sans se soucier des majuscules ni des espaces autour.
     */
    public synchronized TableView joinLocal(String code, Seat seat) {
        Long tableId = code == null ? null : tableByCode.get(code.trim().toUpperCase(Locale.ROOT));
        if (tableId == null) {
            throw new JoinCodeNotFoundException();
        }
        return seat(find(tableId), seat); // ensuite, mêmes règles qu'une table normale (place libre, partie pas commencée…)
    }

    /** Un code d'accès tiré au hasard, différent de ceux des tables existantes. */
    private String newJoinCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(JOIN_CODE_LENGTH);
            for (int i = 0; i < JOIN_CODE_LENGTH; i++) {
                builder.append(JOIN_CODE_ALPHABET.charAt(random.nextInt(JOIN_CODE_ALPHABET.length())));
            }
            code = builder.toString();
        } while (tableByCode.containsKey(code));
        return code;
    }

    public synchronized TableView get(long tableId) {
        return find(tableId).toView();
    }

    /** Rejoint une table du lobby. Une table locale, elle, ne se rejoint qu'avec son code (joinLocal). */
    public synchronized TableView join(long tableId, Seat seat) {
        LobbyTable table = find(tableId);
        // Sans ce test, connaître l'identifiant d'une table locale (1, 2, 3…) suffirait pour s'y asseoir
        if (table.isLocal()) {
            throw new LobbyConflictException("Cette table se rejoint avec son code d'accès");
        }
        return seat(table, seat);
    }

    /** Assoit un joueur à une table, après les vérifications propres à chaque façon de la rejoindre. */
    private TableView seat(LobbyTable table, Seat seat) {
        // Déjà assis à cette table : rien à faire (une requête répétée ne pose pas de problème)
        if (table.isSeated(seat.userId())) {
            return table.toView();
        }

        ensureNotSeatedElsewhere(seat.userId());
        table.join(seat);
        tableByUser.put(seat.userId(), table.id());

        publish(table);
        return table.toView();
    }

    public synchronized void leave(long tableId, Long userId) {
        LobbyTable table = find(tableId);
        if (!table.isSeated(userId)) {
            throw new LobbyConflictException("Tu n'es pas assis à cette table");
        }

        table.leave(userId);
        tableByUser.remove(userId);

        if (table.isEmpty() && !table.isLocal()) {
            tables.remove(tableId);
        }
        publish(table);
    }

    // ------------------------------------------------------------------

    private LobbyTable find(long tableId) {
        LobbyTable table = tables.get(tableId);
        if (table == null) {
            throw new TableNotFoundException(tableId);
        }
        return table;
    }

    private void ensureNotSeatedElsewhere(Long userId) {
        Long currentTable = tableByUser.get(userId);
        if (currentTable != null) {
            throw new LobbyConflictException(
                    "Tu es déjà assis à la table n°" + currentTable + ". Quitte-la d'abord.");
        }
    }

    /** Diffuse la liste à jour, et le détail de la table si elle existe encore. */
    private void publish(LobbyTable table) {
        messaging.convertAndSend(LOBBY_TOPIC, list());
        if (tables.containsKey(table.id())) {
            messaging.convertAndSend(TABLE_TOPIC_PREFIX + table.id(), table.toView());
        }
    }

    /** Réservé aux tests : vide entièrement le lobby. */
    synchronized void clear() {
        tables.clear();
        tableByUser.clear();
        tableByCode.clear();
        nextId = 1;
    }

    public synchronized TableView markPlaying(long tableId, Long userId) {
        LobbyTable table = find(tableId);
        if (!userId.equals(table.ownerId())) {
            throw new LobbyConflictException("Seul le créateur de la table peut lancer la partie");
        }
        if (table.toView().status() == TableStatus.PLAYING) {
            throw new LobbyConflictException("La partie a déjà commencé");
        }
        if (table.playerCount() < 2) {
            throw new LobbyConflictException("Il faut au moins 2 joueurs pour lancer la partie");
        }

        table.markPlaying();
        publish(table);
        return table.toView(); // construite APRÈS la modification : statut PLAYING
    }

    /**
     * Libère un joueur qui a quitté une partie en cours : il peut de nouveau s'asseoir à une table.
     * Sa place reste à la table jusqu'à la fin de la partie (le moteur continue de jouer pour lui).
     */
    public synchronized void releasePlayer(long tableId, Long userId) {
        tableByUser.remove(userId, tableId);
    }

    public synchronized void endGame(long tableId) {
        LobbyTable table = tables.remove(tableId);
        if (table == null) {
            return; // déjà fermée
        }
        if (table.isLocal()) {
            tableByCode.remove(table.joinCode()); // le code ne mène plus nulle part
        }
        for (Seat seat : table.toView().players()) {
            // remove(clé, valeur) : seulement s'il est encore inscrit à CETTE table.
            // Un joueur parti en cours de partie a pu s'asseoir ailleurs depuis : on ne touche pas à sa nouvelle table.
            tableByUser.remove(seat.userId(), tableId);
        }
        publish(table); // la table n'est plus dans la map : seule la liste est diffusée
    }
}