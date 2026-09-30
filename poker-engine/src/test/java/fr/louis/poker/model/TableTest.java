package fr.louis.poker.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableTest {

    private Table table;
    private Player a;
    private Player b;
    private Player c;
    private Player d;

    @BeforeEach
    void setUp() {
        table = new Table(10, 20);
        a = new Player("A", 1000);
        b = new Player("B", 1000);
        c = new Player("C", 1000);
        d = new Player("D", 1000);
    }

    private void seatAll() {
        table.addPlayer(a);
        table.addPlayer(b);
        table.addPlayer(c);
        table.addPlayer(d);
    }

    /** Élimine un joueur : il perd tout son tapis, puis la nouvelle main le passe OUT. */
    private static void eliminate(Player player) {
        player.bet(player.getChips());
        player.resetForNewHand();
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Création et ajout de joueurs")
    class Setup {

        @Test
        @DisplayName("Des blinds invalides sont refusées")
        void invalidBlindsAreRejected() {
            assertThrows(IllegalArgumentException.class, () -> new Table(0, 20));
            assertThrows(IllegalArgumentException.class, () -> new Table(30, 20));
        }

        @Test
        @DisplayName("Les blinds sont bien enregistrées")
        void blindsAreStored() {
            assertEquals(10, table.getSmallBlind());
            assertEquals(20, table.getBigBlind());
        }

        @Test
        @DisplayName("Les joueurs sont assis dans l'ordre d'ajout")
        void playersAreSeatedInOrder() {
            seatAll();
            assertEquals(List.of(a, b, c, d), table.getPlayers());
        }

        @Test
        @DisplayName("Un 11e joueur est refusé")
        void tableIsLimitedToTenPlayers() {
            for (int i = 0; i < 10; i++) {
                table.addPlayer(new Player("J" + i, 1000));
            }
            assertThrows(IllegalStateException.class, () -> table.addPlayer(new Player("J10", 1000)));
        }

        @Test
        @DisplayName("Deux joueurs du même nom sont refusés")
        void duplicateNameIsRejected() {
            table.addPlayer(a);
            assertThrows(IllegalArgumentException.class, () -> table.addPlayer(new Player("A", 500)));
        }

        @Test
        @DisplayName("La liste des joueurs renvoyée ne peut pas être modifiée")
        void playersListIsUnmodifiable() {
            seatAll();
            assertThrows(UnsupportedOperationException.class, () -> table.getPlayers().add(new Player("X", 10)));
        }

        @Test
        @DisplayName("Les joueurs éliminés ne sont plus en jeu")
        void eliminatedPlayersAreNotInHand() {
            seatAll();
            eliminate(b);
            assertEquals(List.of(a, c, d), table.getPlayersInHand());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Bouton du dealer")
    class Button {

        @Test
        @DisplayName("Pas de dealer tant que le bouton n'est pas placé")
        void noDealerBeforeFirstMove() {
            seatAll();
            assertThrows(IllegalStateException.class, () -> table.getDealer());
        }

        @Test
        @DisplayName("Le premier bouton va au siège 0")
        void firstButtonGoesToFirstSeat() {
            seatAll();
            table.moveButton();
            assertEquals(a, table.getDealer());
        }

        @Test
        @DisplayName("Le bouton tourne et revient au siège 0")
        void buttonRotatesAndWraps() {
            seatAll();
            List<Player> dealers = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                table.moveButton();
                dealers.add(table.getDealer());
            }
            assertEquals(List.of(a, b, c, d, a), dealers);
        }

        @Test
        @DisplayName("Le premier bouton saute un siège 0 éliminé")
        void firstButtonSkipsEliminatedSeatZero() {
            seatAll();
            eliminate(a);
            table.moveButton();
            assertEquals(b, table.getDealer());
        }

        @Test
        @DisplayName("Le bouton saute les éliminés en faisant le tour de la table")
        void buttonSkipsEliminatedAndWraps() {
            seatAll();
            table.moveButton(); // A
            table.moveButton(); // B
            table.moveButton(); // C
            eliminate(d);
            eliminate(a);

            table.moveButton();
            assertEquals(b, table.getDealer());
        }

        @Test
        @DisplayName("Impossible de placer le bouton avec moins de 2 joueurs en jeu")
        void cannotMoveButtonWithOnePlayerLeft() {
            table.addPlayer(a);
            table.addPlayer(b);
            eliminate(b);
            assertThrows(IllegalStateException.class, () -> table.moveButton());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Ordre des joueurs après un siège")
    class PlayersAfter {

        @Test
        @DisplayName("Fait le tour de la table et termine par le joueur lui-même")
        void wrapsAroundAndEndsWithPlayer() {
            seatAll();
            assertEquals(List.of(c, d, a, b), table.playersAfter(b));
        }

        @Test
        @DisplayName("Depuis le dernier siège, repart du siège 0")
        void fromLastSeat() {
            seatAll();
            assertEquals(List.of(a, b, c, d), table.playersAfter(d));
        }

        @Test
        @DisplayName("Saute les joueurs éliminés")
        void skipsEliminatedPlayers() {
            seatAll();
            eliminate(c);
            assertEquals(List.of(d, a, b), table.playersAfter(b));
        }

        @Test
        @DisplayName("Un joueur qui n'est pas à la table est refusé")
        void unknownPlayerIsRejected() {
            seatAll();
            assertThrows(IllegalArgumentException.class, () -> table.playersAfter(new Player("X", 100)));
        }
    }
}