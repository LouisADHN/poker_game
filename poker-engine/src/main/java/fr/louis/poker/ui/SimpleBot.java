package fr.louis.poker.ui;

import fr.louis.poker.action.Action;
import fr.louis.poker.action.LegalActions;
import fr.louis.poker.controller.GameView;
import fr.louis.poker.controller.PlayerController;
import fr.louis.poker.evaluation.HandCategory;
import fr.louis.poker.evaluation.HandEvaluator;
import fr.louis.poker.model.Card;
import fr.louis.poker.model.Rank;

import java.util.ArrayList;
import java.util.List;

/**
 * Bot très simple : relance avec une main forte, suit avec une main moyenne
 * si ce n'est pas trop cher, sinon checke ou se couche.
 */
public class SimpleBot implements PlayerController {

    private static final int WEAK = 0;
    private static final int MEDIUM = 1;
    private static final int STRONG = 2;

    @Override
    public Action decide(GameView view) {
        int strength = strength(view);
        LegalActions legal = view.legalActions();

        if (strength == STRONG && legal.canRaise()) {
            int total = legal.minRaiseTo();
            return view.currentBet() == 0 ? new Action.Bet(total) : new Action.Raise(total);
        }
        if (legal.canCheck()) {
            return new Action.Check();
        }
        if (strength == STRONG || (strength == MEDIUM && legal.toCall() <= view.pot() / 2)) {
            return new Action.Call();
        }
        return new Action.Fold();
    }

    private int strength(GameView view) {
        List<Card> hole = view.holeCards();

        // Preflop : on juge uniquement les deux cartes privées
        if (view.board().isEmpty()) {
            Rank first = hole.get(0).rank();
            Rank second = hole.get(1).rank();
            boolean pair = first == second;
            boolean bothHigh = first.getValue() >= 10 && second.getValue() >= 10;
            boolean oneVeryHigh = first.getValue() >= 12 || second.getValue() >= 12;

            if (pair || bothHigh) {
                return STRONG;
            }
            return oneVeryHigh ? MEDIUM : WEAK;
        }

        // Postflop : on évalue la meilleure main avec le board
        List<Card> cards = new ArrayList<>(hole);
        cards.addAll(view.board());
        HandCategory category = HandEvaluator.evaluate(cards).category();

        if (category.compareTo(HandCategory.TWO_PAIR) >= 0) {
            return STRONG;
        }
        return category == HandCategory.ONE_PAIR ? MEDIUM : WEAK;
    }
}