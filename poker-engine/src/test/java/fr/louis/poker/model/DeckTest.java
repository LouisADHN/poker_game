package fr.louis.poker.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeckTest {

    private Deck deck;

    @BeforeEach
    void setUp() {
        deck = new Deck();
    }

    @Test
    @DisplayName("Un paquet neuf contient 52 cartes")
    void newDeckHas52Cards() {
        assertEquals(52, deck.remaining());
    }

    @Test
    @DisplayName("Les 52 cartes sont toutes différentes")
    void allCardsAreDistinct() {
        Set<Card> drawn = new HashSet<>();
        while (deck.remaining() > 0) {
            drawn.add(deck.draw());
        }
        // 52 cartes distinctes parmi 52 possibles = le paquet complet
        assertEquals(52, drawn.size());
    }

    @Test
    @DisplayName("Tirer une carte diminue le nombre de cartes restantes")
    void drawReducesRemaining() {
        deck.draw();
        deck.draw();
        assertEquals(50, deck.remaining());
    }

    @Test
    @DisplayName("Tirer dans un paquet vide lève une exception")
    void drawOnEmptyDeckThrows() {
        for (int i = 0; i < 52; i++) {
            deck.draw();
        }
        assertThrows(IllegalStateException.class, deck::draw);
    }

    @Test
    @DisplayName("Deux paquets mélangés avec la même graine donnent le même ordre")
    void sameSeedGivesSameOrder() {
        Deck other = new Deck();
        deck.shuffle(new Random(42));
        other.shuffle(new Random(42));

        for (int i = 0; i < 52; i++) {
            assertEquals(deck.draw(), other.draw());
        }
    }

    @Test
    @DisplayName("Le mélange modifie l'ordre des cartes")
    void shuffleChangesOrder() {
        Deck unshuffled = new Deck();
        deck.shuffle(new Random(42));

        boolean differenceFound = false;
        for (int i = 0; i < 52; i++) {
            if (!deck.draw().equals(unshuffled.draw())) {
                differenceFound = true;
            }
        }
        assertTrue(differenceFound);
    }
}