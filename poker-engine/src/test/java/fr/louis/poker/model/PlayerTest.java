package fr.louis.poker.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerTest {

    private static final Card ACE_OF_SPADES = new Card(Rank.ACE, Suit.SPADES);
    private static final Card KING_OF_HEARTS = new Card(Rank.KING, Suit.HEARTS);
    private static final Card TWO_OF_CLUBS = new Card(Rank.TWO, Suit.CLUBS);

    private Player player;

    @BeforeEach
    void setUp() {
        player = new Player("Louis", 1000);
    }

    // --- Création ---

    @Test
    @DisplayName("Un nouveau joueur est actif, sans cartes, avec ses jetons")
    void newPlayerIsActiveWithNoCards() {
        assertEquals(PlayerStatus.ACTIVE, player.getStatus());
        assertTrue(player.getHoleCards().isEmpty());
        assertEquals(1000, player.getChips());
        assertEquals("Louis", player.getName());
    }

    @Test
    @DisplayName("Un nom vide ou null est refusé")
    void blankNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Player("", 1000));
        assertThrows(IllegalArgumentException.class, () -> new Player("   ", 1000));
        assertThrows(IllegalArgumentException.class, () -> new Player(null, 1000));
    }

    @Test
    @DisplayName("Un nombre de jetons négatif est refusé")
    void negativeChipsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Player("Louis", -1));
    }

    // --- Cartes ---

    @Test
    @DisplayName("Recevoir une troisième carte lève une exception")
    void receivingThirdCardThrows() {
        player.receiveCard(ACE_OF_SPADES);
        player.receiveCard(KING_OF_HEARTS);
        assertThrows(IllegalStateException.class, () -> player.receiveCard(TWO_OF_CLUBS));
    }

    @Test
    @DisplayName("La liste des cartes renvoyée ne peut pas être modifiée")
    void holeCardsAreUnmodifiable() {
        player.receiveCard(ACE_OF_SPADES);
        assertThrows(UnsupportedOperationException.class,
                () -> player.getHoleCards().add(KING_OF_HEARTS));
    }

    // --- Mises ---

    @Test
    @DisplayName("Miser réduit le tapis")
    void betReducesChips() {
        player.bet(200);
        assertEquals(800, player.getChips());
        assertEquals(PlayerStatus.ACTIVE, player.getStatus());
    }

    @Test
    @DisplayName("Miser tout son tapis fait passer le joueur all-in")
    void bettingAllChipsMakesPlayerAllIn() {
        player.bet(1000);
        assertEquals(0, player.getChips());
        assertEquals(PlayerStatus.ALL_IN, player.getStatus());
    }

    @Test
    @DisplayName("Miser plus que son tapis lève une exception")
    void bettingMoreThanChipsThrows() {
        assertThrows(IllegalArgumentException.class, () -> player.bet(1001));
    }

    @Test
    @DisplayName("Miser zéro ou un montant négatif lève une exception")
    void bettingZeroOrNegativeThrows() {
        assertThrows(IllegalArgumentException.class, () -> player.bet(0));
        assertThrows(IllegalArgumentException.class, () -> player.bet(-50));
    }

    @Test
    @DisplayName("Un joueur couché ne peut plus miser")
    void foldedPlayerCannotBet() {
        player.fold();
        assertEquals(PlayerStatus.FOLDED, player.getStatus());
        assertThrows(IllegalStateException.class, () -> player.bet(100));
    }

    @Test
    @DisplayName("Gagner un pot ajoute des jetons au tapis")
    void winAddsChips() {
        player.win(500);
        assertEquals(1500, player.getChips());
    }

    // --- Nouvelle main ---

    @Test
    @DisplayName("Une nouvelle main vide les cartes et réactive le joueur")
    void resetClearsCardsAndReactivates() {
        player.receiveCard(ACE_OF_SPADES);
        player.receiveCard(KING_OF_HEARTS);
        player.fold();

        player.resetForNewHand();

        assertTrue(player.getHoleCards().isEmpty());
        assertEquals(PlayerStatus.ACTIVE, player.getStatus());
        assertEquals(1000, player.getChips());
    }

    @Test
    @DisplayName("Un joueur sans jetons est éliminé à la nouvelle main")
    void resetWithNoChipsMakesPlayerOut() {
        player.bet(1000);
        player.resetForNewHand();
        assertEquals(PlayerStatus.OUT, player.getStatus());
    }
}