package fr.louis.poker.evaluation;

import fr.louis.poker.model.Card;
import fr.louis.poker.model.Rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Évalue la force d'une main de poker.
 * Classe utilitaire : uniquement des méthodes statiques, pas d'instance.
 */
public final class HandEvaluator {

    /** La roue : A-5-4-3-2, la plus petite quinte (l'as compte pour 1). */
    private static final List<Integer> WHEEL = List.of(14, 5, 4, 3, 2);

    private HandEvaluator() {
        // Empêche l'instanciation
    }

    /**
     * Renvoie la meilleure main de 5 cartes parmi 5 à 7 cartes
     * (typiquement 2 cartes privées + 5 cartes du board).
     */
    public static HandValue evaluate(List<Card> cards) {
        if (cards.size() < 5 || cards.size() > 7) {
            throw new IllegalArgumentException("Il faut entre 5 et 7 cartes, reçu : " + cards.size());
        }
        checkNoDuplicates(cards);

        HandValue best = null;
        for (List<Card> combination : combinationsOfFive(cards)) {
            HandValue value = evaluate5(combination);
            if (best == null || value.compareTo(best) > 0) {
                best = value;
            }
        }
        return best;
    }

    /**
     * Évalue une main d'exactement 5 cartes.
     */
    public static HandValue evaluate5(List<Card> cards) {
        if (cards.size() != 5) {
            throw new IllegalArgumentException("Il faut exactement 5 cartes, reçu : " + cards.size());
        }
        checkNoDuplicates(cards);

        // Regroupe par rang, trie par taille de groupe décroissante puis par rang décroissant.
        // Ex : K 2 K 2 K -> [(K,3), (2,2)]
        List<Map.Entry<Rank, Long>> groups = cards.stream()
                .collect(Collectors.groupingBy(Card::rank, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<Rank, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.<Rank, Long>comparingByKey(Comparator.reverseOrder())))
                .toList();

        // Tailles des groupes (identifient la catégorie) : ex [3, 2]
        List<Integer> sizes = groups.stream()
                .map(e -> e.getValue().intValue())
                .toList();

        // Valeurs des rangs dans l'ordre d'importance (= départages) : ex [13, 2]
        List<Integer> values = groups.stream()
                .map(e -> e.getKey().getValue())
                .toList();

        // Mains à base de groupes
        if (sizes.equals(List.of(4, 1))) {
            return new HandValue(HandCategory.FOUR_OF_A_KIND, values);
        }
        if (sizes.equals(List.of(3, 2))) {
            return new HandValue(HandCategory.FULL_HOUSE, values);
        }
        if (sizes.equals(List.of(3, 1, 1))) {
            return new HandValue(HandCategory.THREE_OF_A_KIND, values);
        }
        if (sizes.equals(List.of(2, 2, 1))) {
            return new HandValue(HandCategory.TWO_PAIR, values);
        }
        if (sizes.equals(List.of(2, 1, 1, 1))) {
            return new HandValue(HandCategory.ONE_PAIR, values);
        }

        // Ici, les 5 rangs sont distincts : quinte, couleur, quinte flush ou carte haute.
        // (Une couleur ne peut pas contenir de paire : même rang + même couleur = même carte.)
        boolean flush = cards.stream().allMatch(c -> c.suit() == cards.get(0).suit());
        boolean wheel = values.equals(WHEEL);
        boolean straight = wheel || values.get(0) - values.get(4) == 4;

        // Une quinte se départage uniquement par sa carte haute (5 pour la roue)
        List<Integer> straightTiebreaker = List.of(wheel ? 5 : values.get(0));

        if (straight && flush) {
            return new HandValue(HandCategory.STRAIGHT_FLUSH, straightTiebreaker);
        }
        if (flush) {
            return new HandValue(HandCategory.FLUSH, values);
        }
        if (straight) {
            return new HandValue(HandCategory.STRAIGHT, straightTiebreaker);
        }
        return new HandValue(HandCategory.HIGH_CARD, values);
    }

    /**
     * Génère toutes les combinaisons de 5 cartes parmi la liste
     * (21 combinaisons pour 7 cartes, 6 pour 6 cartes, 1 pour 5 cartes).
     */
    private static List<List<Card>> combinationsOfFive(List<Card> cards) {
        List<List<Card>> result = new ArrayList<>();
        buildCombinations(cards, 0, new ArrayList<>(), result);
        return result;
    }

    /**
     * Construction récursive : à chaque carte, on choisit de la prendre ou non.
     */
    private static void buildCombinations(List<Card> cards, int start,
                                          List<Card> current, List<List<Card>> result) {
        if (current.size() == 5) {
            result.add(List.copyOf(current));
            return;
        }
        for (int i = start; i < cards.size(); i++) {
            current.add(cards.get(i));
            buildCombinations(cards, i + 1, current, result);
            current.removeLast(); // retour arrière (backtracking)
        }
    }

    private static void checkNoDuplicates(List<Card> cards) {
        if (new HashSet<>(cards).size() != cards.size()) {
            throw new IllegalArgumentException("La main contient des cartes en double : " + cards);
        }
    }
}