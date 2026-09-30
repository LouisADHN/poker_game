package fr.louis.poker.engine;

import fr.louis.poker.model.Player;

import java.util.List;

public record Pot(int amount, List<Player> eligiblePlayers) {

    public Pot {
        eligiblePlayers = List.copyOf(eligiblePlayers);
    }
}
