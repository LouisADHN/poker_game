package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;

/**
 * Action envoyée par le navigateur, par exemple :
 * {"type": "FOLD"} ou {"type": "RAISE", "total": 120}
 *
 * "total" est un Integer et non un int : il est absent (null) pour FOLD, CHECK, CALL et ALL_IN.
 */
public record ActionMessage(String type, Integer total) {

    /** Fait le chemin inverse de GameMessages.action(). */
    public Action toAction() {
        if (type == null) {
            throw new IllegalArgumentException("Le type d'action est obligatoire");
        }
        return switch (type) {
            case "FOLD" -> new Action.Fold();
            case "CHECK" -> new Action.Check();
            case "CALL" -> new Action.Call();
            case "BET" -> new Action.Bet(requireTotal());
            case "RAISE" -> new Action.Raise(requireTotal());
            case "ALL_IN" -> new Action.AllIn();
            default -> throw new IllegalArgumentException("Type d'action inconnu : " + type);
        };
    }

    private int requireTotal() {
        if (total == null) {
            throw new IllegalArgumentException("Le montant est obligatoire pour " + type);
        }
        return total;
    }
}