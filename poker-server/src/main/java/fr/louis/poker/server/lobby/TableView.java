package fr.louis.poker.server.lobby;

import java.util.List;

/**
 * Ce que les clients reçoivent à propos d'une table, en HTTP comme en WebSocket.
 *
 * @param local vrai pour une table du mode local (écran partagé) ; son code d'accès n'est JAMAIS ici, car cette vue est diffusée à tout le lobby
 */
public record TableView(long id,
                        String name,
                        TableSettings settings,
                        Long ownerId,
                        TableStatus status,
                        boolean local,
                        List<Seat> players) {

    public TableView {
        players = List.copyOf(players);
    }
}