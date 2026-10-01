package fr.louis.poker.server.lobby;

import java.util.List;

/**
 * Ce que les clients reçoivent à propos d'une table, en HTTP comme en WebSocket.
 */
public record TableView(long id,
                        String name,
                        TableSettings settings,
                        Long ownerId,
                        TableStatus status,
                        List<Seat> players) {

    public TableView {
        players = List.copyOf(players);
    }
}