package fr.louis.poker.engine;

import fr.louis.poker.evaluation.HandEvaluator;
import fr.louis.poker.evaluation.HandValue;
import fr.louis.poker.model.Card;
import fr.louis.poker.model.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PotManagerTest {

    // Mains de référence, de la plus forte à la plus faible
    private static final HandValue STRONG = eval("As Ad Ah Kc Kd"); // full
    private static final HandValue MEDIUM = eval("Qs Qd 9h 7c 3s"); // paire
    private static final HandValue WEAK = eval("Js Td 8h 6c 2s");   // carte haute

    // Deux mains de même valeur (couleurs différentes) : partage
    private static final HandValue TIE_1 = eval("As Kd 9h 7c 3s");
    private static final HandValue TIE_2 = eval("Ad Kc 9s 7h 3d");

    private static HandValue eval(String cards) {
        return HandEvaluator.evaluate(Card.parseAll(cards));
    }

    private PotManager potManager;
    private Player a;
    private Player b;
    private Player c;
    private Player d;

    @BeforeEach
    void setUp() {
        potManager = new PotManager();
        a = new Player("A", 1000);
        b = new Player("B", 1000);
        c = new Player("C", 1000);
        d = new Player("D", 1000);
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Suivi des contributions")
    class Contributions {

        @Test
        @DisplayName("Les contributions d'un joueur s'additionnent")
        void contributionsAccumulate() {
            potManager.addContribution(a, 100);
            potManager.addContribution(a, 50);
            potManager.addContribution(b, 150);

            assertEquals(150, potManager.getContribution(a));
            assertEquals(150, potManager.getContribution(b));
            assertEquals(300, potManager.getTotal());
        }

        @Test
        @DisplayName("Un joueur qui n'a rien misé a une contribution de 0")
        void unknownPlayerHasZero() {
            assertEquals(0, potManager.getContribution(a));
        }

        @Test
        @DisplayName("Un montant nul ou négatif est refusé")
        void rejectsNonPositiveAmount() {
            assertThrows(IllegalArgumentException.class, () -> potManager.addContribution(a, 0));
            assertThrows(IllegalArgumentException.class, () -> potManager.addContribution(a, -10));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Calcul des pots")
    class ComputePots {

        @Test
        @DisplayName("Sans all-in : un seul pot pour tout le monde")
        void singlePotWhenEqualContributions() {
            potManager.addContribution(a, 100);
            potManager.addContribution(b, 100);
            potManager.addContribution(c, 100);

            assertEquals(List.of(new Pot(300, List.of(a, b, c))), potManager.computePots());
        }

        @Test
        @DisplayName("Un joueur couché alimente le pot mais ne peut pas le gagner")
        void foldedPlayerContributesButIsNotEligible() {
            potManager.addContribution(a, 100);
            potManager.addContribution(b, 100);
            potManager.addContribution(c, 20);
            c.fold();

            assertEquals(List.of(new Pot(220, List.of(a, b))), potManager.computePots());
        }

        @Test
        @DisplayName("L'exemple à 4 joueurs : un pot principal et deux pots secondaires")
        void fourPlayerExample() {
            potManager.addContribution(a, 50);
            potManager.addContribution(b, 150);
            potManager.addContribution(c, 300);
            potManager.addContribution(d, 20);
            d.fold();

            List<Pot> pots = potManager.computePots();

            assertEquals(List.of(
                    new Pot(170, List.of(a, b, c)),
                    new Pot(200, List.of(b, c)),
                    new Pot(150, List.of(c))
            ), pots);
            assertEquals(potManager.getTotal(), pots.stream().mapToInt(Pot::amount).sum());
        }

        @Test
        @DisplayName("Une mise non suivie forme un pot à un seul prétendant")
        void uncalledBetFormsOwnPot() {
            potManager.addContribution(a, 500);
            potManager.addContribution(b, 100); // B all-in, ne peut couvrir que 100

            assertEquals(List.of(
                    new Pot(200, List.of(a, b)),
                    new Pot(400, List.of(a))
            ), potManager.computePots());
        }

        @Test
        @DisplayName("Si tout le monde est couché, c'est une erreur")
        void noPlayerLeftThrows() {
            potManager.addContribution(a, 100);
            a.fold();

            assertThrows(IllegalStateException.class, () -> potManager.computePots());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Distribution des gains")
    class Distribute {

        @Test
        @DisplayName("La meilleure main remporte tout le pot")
        void bestHandWinsEverything() {
            potManager.addContribution(a, 100);
            potManager.addContribution(b, 100);

            Map<Player, Integer> winnings = potManager.distribute(Map.of(a, STRONG, b, WEAK));

            assertEquals(200, winnings.get(a));
            assertFalse(winnings.containsKey(b));
        }

        @Test
        @DisplayName("Un all-in gagne le pot principal mais pas le pot secondaire")
        void allInWinsOnlyMainPot() {
            potManager.addContribution(a, 50);  // A all-in avec la meilleure main
            potManager.addContribution(b, 150);
            potManager.addContribution(c, 150);

            Map<Player, Integer> winnings = potManager.distribute(Map.of(a, STRONG, b, MEDIUM, c, WEAK));

            assertEquals(150, winnings.get(a)); // pot principal : 50 x 3
            assertEquals(200, winnings.get(b)); // pot secondaire : 100 x 2
            assertFalse(winnings.containsKey(c));
        }

        @Test
        @DisplayName("La mise non suivie revient à son auteur même s'il perd")
        void uncalledBetReturnedToLoser() {
            potManager.addContribution(a, 500);
            potManager.addContribution(b, 100);

            Map<Player, Integer> winnings = potManager.distribute(Map.of(a, MEDIUM, b, STRONG));

            assertEquals(200, winnings.get(b));
            assertEquals(400, winnings.get(a));
        }

        @Test
        @DisplayName("L'exemple à 4 joueurs : chaque pot à son gagnant, total conservé")
        void fourPlayerExampleDistribution() {
            potManager.addContribution(a, 50);
            potManager.addContribution(b, 150);
            potManager.addContribution(c, 300);
            potManager.addContribution(d, 20);
            d.fold();

            Map<Player, Integer> winnings = potManager.distribute(Map.of(a, STRONG, b, MEDIUM, c, WEAK));

            assertEquals(170, winnings.get(a));
            assertEquals(200, winnings.get(b));
            assertEquals(150, winnings.get(c)); // C ne récupère que sa mise non suivie
            assertEquals(potManager.getTotal(),
                    winnings.values().stream().mapToInt(Integer::intValue).sum());
        }

        @Test
        @DisplayName("Mains égales : le pot est partagé")
        void equalHandsSplitThePot() {
            potManager.addContribution(a, 100);
            potManager.addContribution(b, 100);

            Map<Player, Integer> winnings = potManager.distribute(Map.of(a, TIE_1, b, TIE_2));

            assertEquals(100, winnings.get(a));
            assertEquals(100, winnings.get(b));
        }

        @Test
        @DisplayName("Partage d'un pot impair : le jeton en trop va au premier gagnant")
        void oddChipGoesToFirstWinner() {
            potManager.addContribution(a, 50);
            potManager.addContribution(b, 50);
            potManager.addContribution(c, 1);
            c.fold();

            Map<Player, Integer> winnings = potManager.distribute(Map.of(a, TIE_1, b, TIE_2));

            assertEquals(51, winnings.get(a));
            assertEquals(50, winnings.get(b));
        }

        @Test
        @DisplayName("Tout le monde s'est couché sauf un : il gagne sans montrer sa main")
        void lastPlayerStandingWinsWithoutShowdown() {
            potManager.addContribution(a, 100);
            potManager.addContribution(b, 300);
            a.fold();

            Map<Player, Integer> winnings = potManager.distribute(Map.of());

            assertEquals(400, winnings.get(b));
        }

        @Test
        @DisplayName("Une main manquante au showdown est une erreur")
        void missingHandThrows() {
            potManager.addContribution(a, 100);
            potManager.addContribution(b, 100);

            assertThrows(IllegalArgumentException.class,
                    () -> potManager.distribute(Map.of(a, STRONG)));
        }
    }
}