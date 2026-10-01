package fr.louis.poker.server.lobby;

import fr.louis.poker.model.Table;

public record TableSettings(int smallBlind,
                           int bigBlind,
                           int startingChips,
                           int maxPlayers) {

    public TableSettings {
        if (smallBlind <= 0) {
            throw new IllegalArgumentException("La small blind doit être positive");
        }
        if (smallBlind > bigBlind) {
            throw new IllegalArgumentException("La small blind ne peut pas dépasser la big blind");
        }
        if (startingChips < 10 * bigBlind) {
            throw new IllegalArgumentException(
                    "Le tapis de départ doit valoir au moins 10 big blinds, soit " + 10 * bigBlind);
        }
        if (maxPlayers < 2 || maxPlayers > Table.MAX_PLAYERS) {
            throw new IllegalArgumentException(
                    "Le nombre de joueurs doit être compris entre 2 et " + Table.MAX_PLAYERS);
        }
    }
}
