package fr.louis.poker.ui;

import fr.louis.poker.action.Action;
import fr.louis.poker.engine.Street;
import fr.louis.poker.evaluation.HandValue;
import fr.louis.poker.event.GameEvent;
import fr.louis.poker.event.GameListener;
import fr.louis.poker.model.Card;

import java.util.List;

public class ConsoleUI implements GameListener {

    private final String viewerName;

    public ConsoleUI(String viewerName) {
        this.viewerName = viewerName;
    }

    @Override
    public void onEvent(GameEvent event) {
        switch (event) {
            case GameEvent.HandStarted(int number, String dealer) ->
                System.out.println("--- Main n°"+ number + " - dealer : " + dealer + " ---");

            case GameEvent.BlindPosted(String player, int amount) ->
                System.out.println(player + " pose la blind de " + amount);

            case GameEvent.HoleCardsDealt(String player, List<Card> cards) ->
            {
                if (player.equals(viewerName)) {
                    System.out.println("Tes cartes : " + cards);
                }
            }

            case GameEvent.BoardDealt(Street street, List<Card> board) ->
                System.out.println(street + " : " + board);

            case GameEvent.PlayerActed(String player, Action action, int streetBet, int chipsLeft) ->
                System.out.println(player + describe(action) + " à " + streetBet + " (reste " + chipsLeft + ")");

            case GameEvent.HandRevealed(String player, List<Card> cards, HandValue value) ->
                System.out.println(player + " montre " + cards + " : " + value.category());

            case GameEvent.PotWon(String player, int amount) ->
                System.out.println(player + " remporte " + amount);

            case GameEvent.HandEnded(int handNumber) ->
                System.out.println();
        }
    }

    private String describe(Action action) {
        return switch (action) {
            case Action.Fold() -> " se couche";
            case Action.Check() -> " checke";
            case Action.Call() -> " suivre";
            case Action.Bet(int total) -> " mise à " + total;
            case Action.Raise(int total) -> " relance à " + total;
            case Action.AllIn() -> " fait tapis";
        };
    }
}
