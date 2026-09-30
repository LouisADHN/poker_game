package fr.louis.poker.evaluation;

import java.util.List;

public record HandValue(HandCategory category, List<Integer> tiebreakers)
        implements Comparable<HandValue> {

    public HandValue {
        tiebreakers = List.copyOf(tiebreakers);
    }

    @Override
    public int compareTo(HandValue other) {
        // 1. Comparer les catégories
        int cmp = this.category.compareTo(other.category);
        if (cmp != 0) {
            return cmp;
        }

        // 2. Si égales, parcourir les départages
        for(int i = 0; i < Math.min(tiebreakers.size(), other.tiebreakers.size()); i++) {
            int cmpTiebreaker = tiebreakers.get(i).compareTo(other.tiebreakers.get(i));
            if (cmpTiebreaker != 0) {
                return cmpTiebreaker;
            }
        }
        // 3. Si tout est égal : 0 (partage du pot)
        return 0;
    }
}
