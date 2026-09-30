package fr.louis.poker.event;

import fr.louis.poker.action.Action;
import fr.louis.poker.engine.Street;
import fr.louis.poker.evaluation.HandValue;
import fr.louis.poker.model.Card;

import java.util.List;

public sealed interface GameEvent {

    record HandStarted(int handNumber, String dealer) implements GameEvent {}

    record BlindPosted(String player, int amount) implements GameEvent {}

    record HoleCardsDealt(String player, List<Card> cards) implements GameEvent {
        public HoleCardsDealt {
            cards = List.copyOf(cards);
        }
    }

    record BoardDealt(Street street, List<Card> board) implements GameEvent {
        public BoardDealt {
            board = List.copyOf(board);
        }
    }

    record PlayerActed(String player, Action action, int streetBet, int chipsLeft) implements GameEvent {}

    record HandRevealed(String player, List<Card> cards, HandValue value) implements GameEvent {
        public HandRevealed {
            cards = List.copyOf(cards);
        }
    }

    record PotWon(String player, int amount) implements GameEvent {}

    record HandEnded(int handNumber) implements GameEvent {}
}
