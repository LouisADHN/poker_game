package fr.louis.poker.evaluation;

import fr.louis.poker.model.Card;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandEvaluatorTest {

    /** Raccourci : évalue une main écrite en notation courte, ex "As Kd Qh Jc Th". */
    private static HandValue eval(String cards) {
        return HandEvaluator.evaluate(Card.parseAll(cards));
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Reconnaissance des catégories")
    class Categories {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "'As Kd 9h 7c 3s', HIGH_CARD",
                "'As Ad 9h 7c 3s', ONE_PAIR",
                "'As Ad 9h 9c 3s', TWO_PAIR",
                "'As Ad Ah 7c 3s', THREE_OF_A_KIND",
                "'9s Td Jh Qc Ks', STRAIGHT",
                "'2h 7h 9h Jh Kh', FLUSH",
                "'As Ad Ah 7c 7s', FULL_HOUSE",
                "'As Ad Ah Ac 3s', FOUR_OF_A_KIND",
                "'5d 6d 7d 8d 9d', STRAIGHT_FLUSH"
        })
        void recognisesCategory(String cards, HandCategory expected) {
            assertEquals(expected, eval(cards).category());
        }

        @Test
        @DisplayName("Les départages d'une double paire : paire haute, paire basse, kicker")
        void twoPairTiebreakers() {
            assertEquals(List.of(14, 7, 13), eval("7s Ah 7d Kc As").tiebreakers());
        }

        @Test
        @DisplayName("Les départages d'un full : brelan puis paire")
        void fullHouseTiebreakers() {
            assertEquals(List.of(2, 14), eval("2s 2d 2h As Ad").tiebreakers());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Quintes : cas particuliers")
    class Straights {

        @Test
        @DisplayName("La roue A-2-3-4-5 est une quinte au 5")
        void wheelIsFiveHighStraight() {
            HandValue wheel = eval("As 2d 3h 4c 5s");
            assertEquals(HandCategory.STRAIGHT, wheel.category());
            assertEquals(List.of(5), wheel.tiebreakers());
        }

        @Test
        @DisplayName("La roue perd contre une quinte au 6")
        void wheelLosesToSixHighStraight() {
            assertTrue(eval("As 2d 3h 4c 5s").compareTo(eval("2c 3d 4h 5c 6s")) < 0);
        }

        @Test
        @DisplayName("La roue à la couleur est une quinte flush au 5")
        void wheelStraightFlush() {
            HandValue value = eval("Ah 2h 3h 4h 5h");
            assertEquals(HandCategory.STRAIGHT_FLUSH, value.category());
            assertEquals(List.of(5), value.tiebreakers());
        }

        @Test
        @DisplayName("Une quinte ne fait pas le tour : Q-K-A-2-3 n'est pas une quinte")
        void noWrapAroundStraight() {
            assertEquals(HandCategory.HIGH_CARD, eval("Qs Ks As 2d 3h").category());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Comparaison de mains de même catégorie")
    class SameCategoryComparisons {

        @Test
        @DisplayName("Paire d'as : le kicker départage")
        void kickerDecides() {
            assertTrue(eval("As Ad Kh Qc 3s").compareTo(eval("Ah Ac Kd Jc 3d")) > 0);
        }

        @Test
        @DisplayName("Full : le brelan compte avant la paire")
        void fullHouseComparesTripsFirst() {
            assertTrue(eval("2s 2d 2h As Ad").compareTo(eval("3s 3d 3h 4c 4d")) < 0);
        }

        @Test
        @DisplayName("Couleur contre couleur : départage jusqu'à la dernière carte")
        void flushComparesAllCards() {
            assertTrue(eval("Ah Kh 9h 7h 3h").compareTo(eval("As Ks 9s 7s 2s")) > 0);
        }

        @Test
        @DisplayName("Mêmes rangs, couleurs différentes : égalité (partage du pot)")
        void identicalRanksSplit() {
            assertEquals(0, eval("As Kd 9h 7c 3s").compareTo(eval("Ad Kc 9s 7h 3d")));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Meilleure main parmi 7 cartes")
    class SevenCards {

        @Test
        @DisplayName("Trouve la quinte flush royale")
        void findsRoyalFlush() {
            HandValue value = eval("Ah Kh Qh Jh Th 2c 3d");
            assertEquals(HandCategory.STRAIGHT_FLUSH, value.category());
            assertEquals(List.of(14), value.tiebreakers());
        }

        @Test
        @DisplayName("Trois paires : garde les deux meilleures et le meilleur kicker")
        void threePairsKeepsBestTwo() {
            HandValue value = eval("Ah Ad Kc Kd Qs Qh 2c");
            assertEquals(HandCategory.TWO_PAIR, value.category());
            assertEquals(List.of(14, 13, 12), value.tiebreakers());
        }

        @Test
        @DisplayName("Deux brelans : forme un full")
        void twoTripsMakeFullHouse() {
            HandValue value = eval("As Ad Ac Ks Kd Kc 2h");
            assertEquals(HandCategory.FULL_HOUSE, value.category());
            assertEquals(List.of(14, 13), value.tiebreakers());
        }

        @Test
        @DisplayName("Six cartes de la même couleur : garde les cinq plus hautes")
        void sixSuitedCardsKeepsTopFive() {
            HandValue value = eval("Ah Kh 9h 7h 4h 2h 3c");
            assertEquals(HandCategory.FLUSH, value.category());
            assertEquals(List.of(14, 13, 9, 7, 4), value.tiebreakers());
        }

        @Test
        @DisplayName("Les deux joueurs jouent le board : partage du pot")
        void bothPlayersPlayTheBoard() {
            String board = "5c 6d 7h 8s 9c";
            HandValue player1 = eval("2h 3d " + board);
            HandValue player2 = eval("Ac Kd " + board);
            assertEquals(0, player1.compareTo(player2));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Entrées invalides")
    class InvalidInput {

        @Test
        @DisplayName("Moins de 5 cartes : exception")
        void tooFewCards() {
            assertThrows(IllegalArgumentException.class, () -> eval("As Kd 9h 7c"));
        }

        @Test
        @DisplayName("Plus de 7 cartes : exception")
        void tooManyCards() {
            assertThrows(IllegalArgumentException.class, () -> eval("As Kd 9h 7c 3s 2d 4h 5c"));
        }

        @Test
        @DisplayName("Cartes en double : exception")
        void duplicateCards() {
            assertThrows(IllegalArgumentException.class, () -> eval("As As Kd 9h 7c"));
        }

        @Test
        @DisplayName("evaluate5 refuse autre chose que 5 cartes")
        void evaluate5RequiresExactlyFive() {
            assertThrows(IllegalArgumentException.class,
                    () -> HandEvaluator.evaluate5(Card.parseAll("As Kd 9h 7c 3s 2d")));
        }
    }
}
