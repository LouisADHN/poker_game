package fr.louis.poker.ui;

import fr.louis.poker.action.Action;
import fr.louis.poker.action.LegalActions;
import fr.louis.poker.controller.GameView;
import fr.louis.poker.controller.OpponentView;
import fr.louis.poker.controller.PlayerController;

import java.util.Scanner;

/**
 * Joueur humain : affiche la situation et lit l'action au clavier.
 * L'action est validée ici, avant d'être envoyée au moteur,
 * pour redemander en cas d'erreur de saisie plutôt que de se faire coucher d'office.
 */
public class ConsoleController implements PlayerController {

    private final Scanner scanner;

    public ConsoleController(Scanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public Action decide(GameView view) {
        printState(view);
        while (true) {
            System.out.print(prompt(view));
            String line = scanner.nextLine().trim().toLowerCase();
            Action action = parse(line, view);
            if (action != null) {
                return action;
            }
            System.out.println("Action invalide, réessaie.");
        }
    }

    private void printState(GameView view) {
        System.out.println();
        System.out.println("=== À toi, " + view.playerName() + " (" + view.street() + ") ===");
        System.out.println("Tes cartes : " + view.holeCards()
                + "   Board : " + (view.board().isEmpty() ? "-" : view.board()));
        System.out.println("Pot : " + view.pot()
                + "   Ton tapis : " + view.chips()
                + "   Ta mise sur ce tour : " + view.myStreetBet());
        for (OpponentView opponent : view.opponents()) {
            System.out.println("  " + opponent.name() + " : " + opponent.chips() + " jetons, mise "
                    + opponent.streetBet() + " (" + opponent.status() + ")");
        }
    }

    private String prompt(GameView view) {
        LegalActions legal = view.legalActions();
        StringBuilder sb = new StringBuilder("[f] se coucher");

        if (legal.canCheck()) {
            sb.append("  [c] checker");
        } else {
            sb.append("  [c] suivre ").append(legal.toCall());
        }

        if (legal.canRaise()) {
            String verb = view.currentBet() == 0 ? "miser" : "relancer";
            sb.append("  [r montant] ").append(verb).append(" à (")
                    .append(legal.minRaiseTo()).append("-").append(legal.maxRaiseTo()).append(")");
        }

        sb.append("  [a] tapis\n> ");
        return sb.toString();
    }

    /** Traduit la saisie en action, ou renvoie null si elle est invalide. */
    private Action parse(String line, GameView view) {
        LegalActions legal = view.legalActions();

        switch (line) {
            case "f" -> {
                return new Action.Fold();
            }
            case "c" -> {
                return legal.canCheck() ? new Action.Check() : new Action.Call();
            }
            case "a" -> {
                return new Action.AllIn();
            }
            default -> {
                // Format attendu : "r 100"
                if (!line.startsWith("r ") || !legal.canRaise()) {
                    return null;
                }
                try {
                    int total = Integer.parseInt(line.substring(2).trim());
                    if (total < legal.minRaiseTo() || total > legal.maxRaiseTo()) {
                        return null;
                    }
                    return view.currentBet() == 0 ? new Action.Bet(total) : new Action.Raise(total);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
    }
}