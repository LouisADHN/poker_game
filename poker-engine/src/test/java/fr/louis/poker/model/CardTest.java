package fr.louis.poker.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CardTest {

    @Test
    public void toStringUsesTwoCharNotation() {
        assertEquals("As", new Card(Rank.ACE, Suit.SPADES).toString());
        assertEquals("Th", new Card(Rank.TEN, Suit.HEARTS).toString());
    }
}
