package fr.louis.poker.controller;

import fr.louis.poker.action.LegalActions;
import fr.louis.poker.engine.Street;
import fr.louis.poker.model.Card;

import java.util.List;

public record GameView(String playerName,
                       List<Card> holeCards,
                       int chips,
                       Street street,
                       List<Card> board,
                       int pot,
                       int currentBet,
                       int myStreetBet,
                       List<OpponentView> opponents,
                       LegalActions legalActions) {

    public GameView {
        holeCards = List.copyOf(holeCards);
        board = List.copyOf(board);
        opponents = List.copyOf(opponents);
    }
}
