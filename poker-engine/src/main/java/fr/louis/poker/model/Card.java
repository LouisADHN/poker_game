package fr.louis.poker.model;

import java.util.ArrayList;
import java.util.List;

public record Card(Rank rank, Suit suit) {

    @Override
    public String toString() {
        return rank.getSymbol() + suit.getSymbol();
    }

    public Card of(String cardString) {
        if (cardString.length() != 2) {
            throw new IllegalArgumentException("La chaine doit faire 2 caractères");
        }
        String rankCard = cardString.substring(0, 1);
        String suitCard = cardString.substring(1, 2);
        Card cardFormat;
        try {
            cardFormat = new Card(Rank.valueOf(rankCard), Suit.valueOf(suitCard));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("La carte n'est pas valide");
        }
        return cardFormat;
    }

    public List<Card> parseAll(String cardsString) {
        List<Card> cards = new ArrayList<>();
        for (String card : cardsString.split(" ")) {
            cards.add(of(card));
        }
        return cards;
    }
}
