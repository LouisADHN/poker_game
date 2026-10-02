package fr.louis.poker.server.lobby;

import java.util.ArrayList;
import java.util.List;

/**
 * Une table du lobby : son nom, ses paramètres et les joueurs assis.
 *
 * Cette classe n'est PAS thread-safe à elle seule : elle est toujours manipulée
 * sous le verrou du LobbyService (méthodes synchronized), jamais directement.
 * C'est pourquoi ses méthodes sont package-private.
 */
class LobbyTable {

    private final long id;
    private final String name;
    private final TableSettings settings;
    private final List<Seat> seats = new ArrayList<>();

    private Long ownerId;
    private TableStatus status = TableStatus.WAITING;

    LobbyTable(long id, String name, TableSettings settings, Seat creator) {
        this.id = id;
        this.name = name;
        this.settings = settings;
        this.seats.add(creator);
        this.ownerId = creator.userId();
    }

    long id() {
        return id;
    }

    boolean isSeated(Long userId) {
        return seats.stream().anyMatch(seat -> seat.userId().equals(userId));
    }

    boolean isEmpty() {
        return seats.isEmpty();
    }

    void join(Seat seat) {
        if (status != TableStatus.WAITING) {
            throw new LobbyConflictException("La partie a déjà commencé à cette table");
        }
        if (seats.size() >= settings.maxPlayers()) {
            throw new LobbyConflictException("La table est complète");
        }
        seats.add(seat);
    }

    void leave(Long userId) {
        if (status == TableStatus.PLAYING) {
            throw new LobbyConflictException("Impossible de quitter la table pendant une partie");
        }
        seats.removeIf(seat -> seat.userId().equals(userId));

        // Le créateur est parti : la table revient au joueur assis depuis le plus longtemps
        if (userId.equals(ownerId) && !seats.isEmpty()) {
            ownerId = seats.getFirst().userId();
        }
    }

    TableView toView() {
        return new TableView(id, name, settings, ownerId, status, List.copyOf(seats));
    }

    Long ownerId() {
        return ownerId;
    }

    int playerCount() {
        return seats.size();
    }

    void markPlaying() {
        status = TableStatus.PLAYING;
    }
}