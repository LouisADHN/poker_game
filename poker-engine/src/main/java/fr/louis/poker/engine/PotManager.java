package fr.louis.poker.engine;

import fr.louis.poker.evaluation.HandValue;
import fr.louis.poker.model.Player;
import fr.louis.poker.model.PlayerStatus;

import java.util.*;

public class PotManager {

    private final Map<Player, Integer> contributions = new LinkedHashMap<>();

    public void addContribution(Player player, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Le montant doit être positif : " + amount);
        }
        contributions.merge(player, amount, Integer::sum);
    }

    public int getContribution(Player player) {
        return contributions.getOrDefault(player, 0);
    }

    public int getTotal() {
        return contributions.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * Découpe le total en pots successifs.
     * Chaque niveau correspond à la contribution d'un joueur encore en course ;
     * chaque tranche entre deux niveaux forme un pot.
     */
    public List<Pot> computePots() {
        // 1. Les niveaux : contributions distinctes des joueurs non couchés, triées
        List<Integer> levels = contributions.entrySet().stream()
                .filter(e -> !isFolded(e.getKey()))
                .map(Map.Entry::getValue)
                .distinct()
                .sorted()
                .toList();

        if (levels.isEmpty()) {
            throw new IllegalStateException("Aucun joueur n'est encore en course");
        }

        // 2. Une tranche de pot par niveau
        List<Pot> pots = new ArrayList<>();
        int previousLevel = 0;

        for (int level : levels) {
            int amount = 0;
            List<Player> eligible = new ArrayList<>();

            for (Map.Entry<Player, Integer> entry : contributions.entrySet()) {
                Player player = entry.getKey();
                int contribution = entry.getValue();

                // Part de ce joueur dans la tranche ]previousLevel, level]
                amount += Math.min(contribution, level) - Math.min(contribution, previousLevel);

                // Éligible s'il est en course et a couvert tout le niveau
                if (!isFolded(player) && contribution >= level) {
                    eligible.add(player);
                }
            }

            pots.add(new Pot(amount, eligible));
            previousLevel = level;
        }

        // 3. Cas théorique : un joueur couché aurait misé plus que tous les joueurs
        //    encore en course. Son excédent n'est dans aucune tranche : on l'ajoute au dernier pot.
        int distributed = pots.stream().mapToInt(Pot::amount).sum();
        int leftover = getTotal() - distributed;
        if (leftover > 0) {
            Pot last = pots.removeLast();
            pots.add(new Pot(last.amount() + leftover, last.eligiblePlayers()));
        }

        return pots;
    }

    /**
     * Calcule les gains de chaque joueur au showdown, sans modifier les tapis.
     *
     * @param hands la meilleure main de chaque joueur encore en course
     *              (peut être vide si un seul joueur reste : il gagne sans montrer)
     * @return les gains par joueur (les joueurs qui ne gagnent rien sont absents)
     */
    public Map<Player, Integer> distribute(Map<Player, HandValue> hands) {
        Map<Player, Integer> winnings = new LinkedHashMap<>();

        for (Pot pot : computePots()) {
            List<Player> winners = findWinners(pot.eligiblePlayers(), hands);

            int share = pot.amount() / winners.size();
            int remainder = pot.amount() % winners.size();

            // Les jetons en trop vont un par un aux premiers gagnants
            for (int i = 0; i < winners.size(); i++) {
                int gain = share + (i < remainder ? 1 : 0);
                winnings.merge(winners.get(i), gain, Integer::sum);
            }
        }
        return winnings;
    }

    private List<Player> findWinners(List<Player> contenders, Map<Player, HandValue> hands) {
        // Un seul prétendant : il gagne sans avoir besoin de montrer sa main
        if (contenders.size() == 1) {
            return contenders;
        }

        for (Player player : contenders) {
            if (!hands.containsKey(player)) {
                throw new IllegalArgumentException("Main manquante pour " + player);
            }
        }

        HandValue best = contenders.stream()
                .map(hands::get)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        return contenders.stream()
                .filter(p -> hands.get(p).compareTo(best) == 0)
                .toList();
    }

    private static boolean isFolded(Player player) {
        return player.getStatus() == PlayerStatus.FOLDED;
    }
}
