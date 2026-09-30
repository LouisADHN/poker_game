package fr.louis.poker.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CardTest {

    @Test
    public void toStringUsesTwoCharNotation() {
        assertEquals("As", new Card(Rank.ACE, Suit.SPADES).toString());
        assertEquals("Th", new Card(Rank.TEN, Suit.HEARTS).toString());
    }
}
