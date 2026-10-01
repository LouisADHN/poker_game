package fr.louis.poker.server.lobby;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** LinkedHashMap : les tables sont listées dans l'ordre de création. */
    private final Map<Long, LobbyTable> tables = new LinkedHashMap<>();

    /** Table où chaque joueur est assis : userId -> tableId. */
    private final Map<Long, Long> tableByUser = new HashMap<>();

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

    public synchronized List<TableView> list() {
        return tables.values().stream().map(LobbyTable::toView).toList();
    }

    public synchronized TableView get(long tableId) {
        return find(tableId).toView();
    }

    public synchronized TableView join(long tableId, Seat seat) {
        LobbyTable table = find(tableId);

        // Déjà assis à cette table : rien à faire (une requête répétée ne pose pas de problème)
        if (table.isSeated(seat.userId())) {
            return table.toView();
        }

        ensureNotSeatedElsewhere(seat.userId());
        table.join(seat);
        tableByUser.put(seat.userId(), tableId);

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

        if (table.isEmpty()) {
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
        nextId = 1;
    }
}