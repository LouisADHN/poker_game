package fr.louis.poker.server.game;

import java.util.List;

/**
 * Publié par GameService quand une partie démarre, juste avant que la première main soit jouée.
 *
 * Le ConnectionRegistry l'écoute pour repérer les joueurs déjà déconnectés au lancement :
 * sans lui, la partie les croirait présents et attendrait chacun de leurs tours.
 *
 * @param userIds les joueurs de la partie
 */
public record GameStartedEvent(long tableId, List<Long> userIds) {

    public GameStartedEvent {
        userIds = List.copyOf(userIds);
    }
}