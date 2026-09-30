package fr.louis.poker.engine;

public enum Street {
    PREFLOP(0),
    FLOP(3),
    TURN(1),
    RIVER(1);

    private final int nbCardReturned;

    Street(int CardsToDeal) {
        nbCardReturned = CardsToDeal;
    }

    public int getCardsToDeal() {
        return nbCardReturned;
    }
}
