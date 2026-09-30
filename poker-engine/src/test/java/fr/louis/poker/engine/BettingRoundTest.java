package fr.louis.poker.engine;

import fr.louis.poker.action.Action;
import fr.louis.poker.action.LegalActions;
import fr.louis.poker.model.Player;
import fr.louis.poker.model.PlayerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Blinds 10/20. À 3 joueurs : A est dealer, B small blind, C big blind.
 * Preflop, A parle en premier (ordre A, B, C).
 * Postflop, B parle en premier (ordre B, C, A).
 */
class BettingRoundTest {

    private static final int BIG_BLIND = 20;

    private PotManager pot;
    private Player a;
    private Player b;
    private Player c;

    @BeforeEach
    void setUp() {
        pot = new PotManager();
        a = new Player("A", 1000);
        b = new Player("B", 1000);
        c = new Player("C", 1000);
    }

    private BettingRound preflop() {
        BettingRound round = new BettingRound(List.of(a, b, c), pot, BIG_BLIND);
        round.postBlind(b, 10);
        round.postBlind(c, 20);
        return round;
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Preflop")
    class Preflop {

        @Test
        @DisplayName("Le joueur après la big blind parle en premier")
        void firstToActIsAfterBigBlind() {
            assertEquals(a, preflop().currentPlayer());
        }

        @Test
        @DisplayName("Les blinds sont bien prélevées et ajoutées au pot")
        void blindsArePosted() {
            BettingRound round = preflop();
            assertEquals(990, b.getChips());
            assertEquals(980, c.getChips());
            assertEquals(30, pot.getTotal());
            assertEquals(20, round.getCurrentBet());
        }

        @Test
        @DisplayName("Actions légales du premier joueur : suivre 20 ou relancer à 40 minimum")
        void legalActionsForFirstPlayer() {
            LegalActions legal = preflop().legalActions();
            assertFalse(legal.canCheck());
            assertEquals(20, legal.toCall());
            assertTrue(legal.canRaise());
            assertEquals(40, legal.minRaiseTo());
            assertEquals(1000, legal.maxRaiseTo());
        }

        @Test
        @DisplayName("Option de la big blind : elle peut encore parler si tout le monde a suivi")
        void bigBlindOption() {
            BettingRound round = preflop();
            round.apply(new Action.Call());  // A
            round.apply(new Action.Call());  // B complète sa small blind

            assertFalse(round.isComplete());
            assertEquals(c, round.currentPlayer());
            assertTrue(round.legalActions().canCheck());
            assertTrue(round.legalActions().canRaise());

            round.apply(new Action.Check()); // C
            assertTrue(round.isComplete());
            assertEquals(60, pot.getTotal());
        }

        @Test
        @DisplayName("Tout le monde se couche : la big blind gagne, le tour est fini")
        void everyoneFoldsToBigBlind() {
            BettingRound round = preflop();
            round.apply(new Action.Fold()); // A
            round.apply(new Action.Fold()); // B
            assertTrue(round.isComplete());
        }

        @Test
        @DisplayName("Une relance oblige les joueurs ayant déjà parlé à reparler")
        void raiseReopensAction() {
            BettingRound round = preflop();
            round.apply(new Action.Call());      // A : 20
            round.apply(new Action.Call());      // B : 20
            round.apply(new Action.Raise(60));   // C relance à 60

            assertFalse(round.isComplete());
            assertEquals(a, round.currentPlayer());
            assertEquals(40, round.legalActions().toCall());

            round.apply(new Action.Call());      // A
            round.apply(new Action.Call());      // B
            assertTrue(round.isComplete());
            assertEquals(180, pot.getTotal());
        }

        @Test
        @DisplayName("Checker face à une mise est interdit")
        void cannotCheckFacingBet() {
            assertThrows(IllegalArgumentException.class, () -> preflop().apply(new Action.Check()));
        }

        @Test
        @DisplayName("Bet est interdit s'il y a déjà une mise (preflop : la big blind)")
        void cannotBetWhenBetExists() {
            assertThrows(IllegalArgumentException.class, () -> preflop().apply(new Action.Bet(40)));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Relances")
    class Raises {

        @Test
        @DisplayName("La relance minimum suit la taille de la relance précédente")
        void minRaiseFollowsPreviousRaise() {
            BettingRound round = preflop();
            round.apply(new Action.Raise(60)); // A relance de +40

            assertEquals(100, round.legalActions().minRaiseTo()); // B doit aller à 100 minimum
            assertThrows(IllegalArgumentException.class, () -> round.apply(new Action.Raise(80)));
        }

        @Test
        @DisplayName("Relancer plus que son tapis est interdit")
        void cannotRaiseMoreThanStack() {
            assertThrows(IllegalArgumentException.class, () -> preflop().apply(new Action.Raise(1001)));
        }

        @Test
        @DisplayName("La relance prélève le bon montant sur le tapis")
        void raiseTakesCorrectAmount() {
            BettingRound round = preflop();
            round.apply(new Action.Raise(60));
            assertEquals(940, a.getChips());
            assertEquals(60, round.getStreetBet(a));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Postflop")
    class Postflop {

        private BettingRound postflop() {
            return new BettingRound(List.of(b, c, a), pot, BIG_BLIND);
        }

        @Test
        @DisplayName("Sans mise : on peut checker, la mise minimum est la big blind")
        void noBetYet() {
            LegalActions legal = postflop().legalActions();
            assertTrue(legal.canCheck());
            assertEquals(0, legal.toCall());
            assertEquals(20, legal.minRaiseTo());
        }

        @Test
        @DisplayName("Après une mise de 50, la relance minimum est à 100")
        void betSetsMinRaise() {
            BettingRound round = postflop();
            round.apply(new Action.Bet(50)); // B

            LegalActions legal = round.legalActions(); // C
            assertEquals(50, legal.toCall());
            assertEquals(100, legal.minRaiseTo());
        }

        @Test
        @DisplayName("Trois checks terminent le tour")
        void allChecksEndRound() {
            BettingRound round = postflop();
            round.apply(new Action.Check());
            round.apply(new Action.Check());
            round.apply(new Action.Check());
            assertTrue(round.isComplete());
            assertEquals(0, pot.getTotal());
        }

        @Test
        @DisplayName("Raise est interdit quand personne n'a misé : il faut Bet")
        void cannotRaiseWithoutBet() {
            assertThrows(IllegalArgumentException.class, () -> postflop().apply(new Action.Raise(40)));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("All-in")
    class AllIn {

        @Test
        @DisplayName("Un all-in inférieur à la mise ne rouvre rien et le tour se termine")
        void shortAllInCall() {
            Player shortStack = new Player("S", 30);
            BettingRound round = new BettingRound(List.of(a, b, shortStack), pot, BIG_BLIND);

            round.apply(new Action.Bet(100));  // A
            round.apply(new Action.Call());    // B
            round.apply(new Action.AllIn());   // S : 30 seulement

            assertEquals(PlayerStatus.ALL_IN, shortStack.getStatus());
            assertTrue(round.isComplete());
            assertEquals(230, pot.getTotal());
        }

        @Test
        @DisplayName("Un all-in incomplet : ceux qui ont déjà parlé suivent mais ne relancent pas")
        void incompleteAllInDoesNotReopen() {
            Player shortStack = new Player("S", 130);
            BettingRound round = new BettingRound(List.of(a, shortStack, b), pot, BIG_BLIND);

            round.apply(new Action.Bet(100));  // A mise 100
            round.apply(new Action.AllIn());   // S fait tapis à 130 (+30 < relance min de 100)

            // B n'avait pas encore parlé : il peut relancer
            assertTrue(round.legalActions().canRaise());
            round.apply(new Action.Call());    // B suit 130

            // A avait déjà parlé : il doit suivre 30 mais ne peut pas relancer
            assertEquals(a, round.currentPlayer());
            LegalActions legal = round.legalActions();
            assertEquals(30, legal.toCall());
            assertFalse(legal.canRaise());
            assertThrows(IllegalArgumentException.class, () -> round.apply(new Action.Raise(300)));
            assertThrows(IllegalArgumentException.class, () -> round.apply(new Action.AllIn()));

            round.apply(new Action.Call());
            assertTrue(round.isComplete());
            assertEquals(390, pot.getTotal());
        }

        @Test
        @DisplayName("Un all-in qui est une relance complète rouvre les enchères")
        void fullAllInRaiseReopens() {
            Player bigAllIn = new Player("S", 300);
            BettingRound round = new BettingRound(List.of(a, bigAllIn, b), pot, BIG_BLIND);

            round.apply(new Action.Bet(100));  // A
            round.apply(new Action.AllIn());   // S à 300 (+200 >= 100 : relance complète)
            round.apply(new Action.Call());    // B suit 300

            assertEquals(a, round.currentPlayer());
            assertTrue(round.legalActions().canRaise());
        }

        @Test
        @DisplayName("Les joueurs all-in sont sautés dans l'ordre de parole")
        void allInPlayersAreSkipped() {
            Player shortStack = new Player("S", 50);
            BettingRound round = new BettingRound(List.of(shortStack, a, b), pot, BIG_BLIND);

            round.apply(new Action.AllIn());   // S : 50
            round.apply(new Action.Call());    // A
            round.apply(new Action.Raise(200)); // B

            assertEquals(a, round.currentPlayer()); // S est sauté
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Fin de tour")
    class EndOfRound {

        @Test
        @DisplayName("Agir après la fin du tour est une erreur")
        void cannotActAfterCompletion() {
            BettingRound round = preflop();
            round.apply(new Action.Fold());
            round.apply(new Action.Fold());

            assertThrows(IllegalStateException.class, round::currentPlayer);
            assertThrows(IllegalStateException.class, () -> round.apply(new Action.Check()));
        }

        @Test
        @DisplayName("Les blinds ne peuvent plus être posées après la première action")
        void cannotPostBlindAfterStart() {
            BettingRound round = preflop();
            round.apply(new Action.Call());
            assertThrows(IllegalStateException.class, () -> round.postBlind(a, 20));
        }
    }
}