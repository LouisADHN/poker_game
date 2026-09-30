package fr.louis.poker.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public record Card(Rank rank, Suit suit) {

    @Override
    public String toString() {
        return rank.getSymbol() + suit.getSymbol();
    }

    public static Card of(String s) {
        if (s == null || s.length() != 2) {
            throw new IllegalArgumentException("Carte invalide : " + s);
        }
        return new Card(Rank.fromSymbol(s.substring(0, 1)), Suit.fromSymbol(s.substring(1)));
    }

    public static List<Card> parseAll(String s) {
        return Arrays.stream(s.trim().split("\\s+")).map(Card::of).toList();
    }
}
