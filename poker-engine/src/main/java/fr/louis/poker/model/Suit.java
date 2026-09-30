package fr.louis.poker.model;

public enum Suit {
    CLUBS("c"),
    DIAMONDS("d"),
    HEARTS("h"),
    SPADES("s");

    private final String symbol;

    Suit(String symbol) {
        this.symbol = symbol;
    }

    public String getSymbol() {
        return symbol;
    }

    public static Suit fromSymbol(String symbol) {
        for (Suit suit : values()) {
            if (suit.symbol.equals(symbol)) {
                return suit;
            }
        }
        throw new IllegalArgumentException("Rang inconnu : " + symbol);
    }
}
