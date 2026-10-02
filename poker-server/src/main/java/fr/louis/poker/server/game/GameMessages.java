package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.controller.GameView;
import fr.louis.poker.event.GameEvent;
import fr.louis.poker.model.Card;

import java.util.List;
import java.util.Map;

public class GameMessages {



    private GameMessages() {
    }

    public static List<String> cards(List<Card> cards) {
        return cards.stream().map(Card::toString).toList();
    }

    public static Map<String, Object> action(Action action) {
        return switch (action) {
          case Action.Fold() -> Map.of("type", "FOLD");
          case Action.Check() -> Map.of("type", "CHECK");
          case Action.Call() -> Map.of("type", "CALL");
          case Action.Bet(int total) -> Map.of("type", "BET", "total", total);
          case Action.Raise(int total) -> Map.of("type", "RAISE", "total", total);
          case Action.AllIn() -> Map.of("type", "ALL_IN");
        };
    }

    public static GameMessage fromEvent(GameEvent event) {
        return switch (event) {
          case GameEvent.HandStarted(int handNumber, String dealer) ->
                  new GameMessage("HAND_STARTED", Map.of(
                          "handNumber", handNumber,
                          "dealer", dealer));
          case GameEvent.BlindPosted(String player, int amount) ->
                  new GameMessage("BLIND_POSTED", Map.of(
                          "player", player,
                          "ammout", amount));
            case GameEvent.HoleCardsDealt(String player, List<Card> holeCards) ->
                    new GameMessage("HOLE_CARDS", Map.of(
                            "player", player,
                            "cards", cards(holeCards)));

            case GameEvent.BoardDealt(var street, List<Card> board) ->
                    new GameMessage("BOARD", Map.of(
                            "street", street.name(),
                            "board", cards(board)));

            case GameEvent.PlayerActed(String player, Action playerAction, int streetBet, int chipsLeft) ->
                    new GameMessage("PLAYER_ACTED", Map.of(
                            "player", player,
                            "action", action(playerAction),
                            "streetBet", streetBet,
                            "chipsLeft", chipsLeft));

            case GameEvent.HandRevealed(String player, List<Card> holeCards, var value) ->
                    new GameMessage("HAND_REVEALED", Map.of(
                            "player", player,
                            "cards", cards(holeCards),
                            "category", value.category().name()));

            case GameEvent.PotWon(String player, int amount) ->
                    new GameMessage("POT_WON", Map.of(
                            "player", player,
                            "amount", amount));

            case GameEvent.HandEnded(int handNumber) ->
                    new GameMessage("HAND_ENDED", Map.of(
                            "handNumber", handNumber));
        };
    }

    public static GameMessage yourTurn(GameView view) {
        return new GameMessage("YOUR_TURN", Map.of(
                "street", view.street().name(),
                "holeCards", cards(view.holeCards()),
                "board", cards(view.board()),
                "chips", view.chips(),
                "pot", view.pot(),
                "currentBet", view.currentBet(),
                "myStreetBet", view.myStreetBet(),
                "opponents", view.opponents(),
                "legalActions", view.legalActions()));
    }
}
