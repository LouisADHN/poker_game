package fr.louis.poker.engine;

import fr.louis.poker.action.Action;
import fr.louis.poker.action.LegalActions;
import fr.louis.poker.model.Player;
import fr.louis.poker.model.PlayerStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gère un tour d'enchères (preflop, flop, turn ou river).
 * Reçoit les joueurs dans l'ordre de parole : le premier de la liste parle en premier.
 * Un nouveau BettingRound est créé pour chaque tour.
 */
public class BettingRound {

    private final List<Player> players;
    private final PotManager potManager;
    private final int bigBlind;

    /** Ce que chaque joueur a misé sur CE tour (remis à zéro à chaque tour). */
    private final Map<Player, Integer> streetBets = new HashMap<>();

    /** Joueurs ayant parlé depuis la dernière relance complète. */
    private final Set<Player> hasActed = new HashSet<>();

    /** La plus haute mise du tour. */
    private int currentBet = 0;

    /** Taille de la dernière relance complète (la big blind en début de tour). */
    private int minRaise;

    private int currentIndex;
    private boolean started = false;

    public BettingRound(List<Player> playersInOrder, PotManager potManager, int bigBlind) {
        if (playersInOrder.size() < 2) {
            throw new IllegalArgumentException("Il faut au moins 2 joueurs");
        }
        if (bigBlind <= 0) {
            throw new IllegalArgumentException("La big blind doit être positive");
        }
        this.players = List.copyOf(playersInOrder);
        this.potManager = potManager;
        this.bigBlind = bigBlind;
        this.minRaise = bigBlind;

        // On part "juste avant" le premier joueur pour tomber sur le premier actif
        this.currentIndex = players.size() - 1;
        moveToNextActive();
    }

    /**
     * Pose une blind (ou une mise forcée) avant le début des actions.
     * Poser sa blind ne compte PAS comme avoir parlé : c'est l'option de la big blind.
     */
    public void postBlind(Player player, int amount) {
        if (started) {
            throw new IllegalStateException("Les blinds se posent avant la première action");
        }
        if (!players.contains(player)) {
            throw new IllegalArgumentException(player + " ne participe pas à ce tour");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Le montant de la blind doit être positif");
        }

        // Un joueur trop court paie ce qu'il a (et passe all-in)
        pay(player, Math.min(amount, player.getChips()));

        // La mise à suivre reste la blind complète, même si le joueur était trop court
        currentBet = Math.max(currentBet, amount);

        // Si le premier à parler vient de passer all-in en posant sa blind, on passe au suivant
        if (players.get(currentIndex).getStatus() != PlayerStatus.ACTIVE) {
            moveToNextActive();
        }
    }

    public Player currentPlayer() {
        if (isComplete()) {
            throw new IllegalStateException("Le tour d'enchères est terminé");
        }
        return players.get(currentIndex);
    }

    /** Ce que le joueur courant a le droit de faire. */
    public LegalActions legalActions() {
        Player player = currentPlayer();
        int streetBet = betOf(player);

        boolean canCheck = streetBet == currentBet;
        int toCall = Math.min(currentBet - streetBet, player.getChips());
        int minRaiseTo = currentBet == 0 ? bigBlind : currentBet + minRaise;
        int maxRaiseTo = streetBet + player.getChips();

        // On ne peut relancer que si on n'a pas parlé depuis la dernière relance complète
        // (règle du all-in incomplet) et si on a assez pour une relance minimum
        boolean canRaise = !hasActed.contains(player) && maxRaiseTo >= minRaiseTo;

        return new LegalActions(canCheck, toCall, canRaise, minRaiseTo, maxRaiseTo);
    }

    /** Applique l'action du joueur courant, puis passe la parole au suivant. */
    public void apply(Action action) {
        Player player = currentPlayer();
        LegalActions legal = legalActions();

        switch (action) {
            case Action.Fold() -> player.fold();
            case Action.Check() -> check(player, legal);
            case Action.Call() -> call(player, legal);
            case Action.Bet(int total) -> {
                if (currentBet != 0) {
                    throw new IllegalArgumentException("Il y a déjà une mise : utilise Raise");
                }
                raiseTo(player, total, legal);
            }
            case Action.Raise(int total) -> {
                if (currentBet == 0) {
                    throw new IllegalArgumentException("Aucune mise à relancer : utilise Bet");
                }
                raiseTo(player, total, legal);
            }
            case Action.AllIn() -> allIn(player);
        }

        started = true;
        if (!isComplete()) {
            moveToNextActive();
        }
    }

    /**
     * Le tour est terminé si :
     * - il reste au plus un joueur non couché, ou
     * - aucun joueur ne peut plus agir, ou
     * - tous les joueurs actifs ont parlé depuis la dernière relance complète
     *   ET ont égalé la mise courante.
     */
    public boolean isComplete() {
        long stillInHand = players.stream()
                .filter(p -> p.getStatus() == PlayerStatus.ACTIVE || p.getStatus() == PlayerStatus.ALL_IN)
                .count();
        if (stillInHand <= 1) {
            return true;
        }

        List<Player> active = players.stream()
                .filter(p -> p.getStatus() == PlayerStatus.ACTIVE)
                .toList();

        if (active.isEmpty()) {
            return true;
        }

        // Un seul joueur peut encore agir : s'il a égalé, il n'a personne contre qui miser
        if (active.size() == 1) {
            return betOf(active.getFirst()) >= currentBet;
        }

        return active.stream().allMatch(p -> hasActed.contains(p) && betOf(p) == currentBet);
    }

    public int getCurrentBet() {
        return currentBet;
    }

    public int getStreetBet(Player player) {
        return betOf(player);
    }

    // ------------------------------------------------------------------
    // Actions

    private void check(Player player, LegalActions legal) {
        if (!legal.canCheck()) {
            throw new IllegalArgumentException("Impossible de checker : il faut suivre " + legal.toCall());
        }
        hasActed.add(player);
    }

    private void call(Player player, LegalActions legal) {
        if (legal.canCheck()) {
            throw new IllegalArgumentException("Rien à suivre : utilise Check");
        }
        pay(player, legal.toCall()); // si le tapis est trop court, Player passe ALL_IN tout seul
        hasActed.add(player);
    }

    /** Mise ou relance à un total donné (Bet et Raise partagent cette logique). */
    private void raiseTo(Player player, int total, LegalActions legal) {
        if (!legal.canRaise()) {
            throw new IllegalArgumentException("Relance impossible pour " + player);
        }
        if (total < legal.minRaiseTo() || total > legal.maxRaiseTo()) {
            throw new IllegalArgumentException("Le total doit être entre "
                    + legal.minRaiseTo() + " et " + legal.maxRaiseTo() + ", reçu : " + total);
        }
        applyFullRaise(player, total);
    }

    private void allIn(Player player) {
        int total = betOf(player) + player.getChips();

        if (total <= currentBet) {
            // All-in pour suivre (ou pour moins que la mise) : simple call
            pay(player, player.getChips());
            hasActed.add(player);
            return;
        }

        // L'all-in dépasse la mise courante : c'est une relance
        if (hasActed.contains(player)) {
            throw new IllegalArgumentException(player + " ne peut plus relancer (enchères non rouvertes)");
        }

        int increment = total - currentBet;
        if (increment >= minRaise) {
            // Relance complète : rouvre les enchères
            applyFullRaise(player, total);
        } else {
            // Relance incomplète : la mise monte, mais les enchères ne sont PAS rouvertes
            pay(player, player.getChips());
            currentBet = total;
            hasActed.add(player);
        }
    }

    private void applyFullRaise(Player player, int total) {
        int increment = total - currentBet;
        pay(player, total - betOf(player));
        minRaise = increment;
        currentBet = total;

        // Tout le monde doit reparler, sauf le relanceur
        hasActed.clear();
        hasActed.add(player);
    }

    // ------------------------------------------------------------------
    // Utilitaires

    /** Retire les jetons du tapis, les ajoute au pot et à la mise du tour. */
    private void pay(Player player, int amount) {
        player.bet(amount);
        potManager.addContribution(player, amount);
        streetBets.merge(player, amount, Integer::sum);
    }

    private int betOf(Player player) {
        return streetBets.getOrDefault(player, 0);
    }

    /** Avance jusqu'au prochain joueur ACTIVE (les couchés et all-in sont sautés). */
    private void moveToNextActive() {
        for (int i = 1; i <= players.size(); i++) {
            int index = (currentIndex + i) % players.size();
            if (players.get(index).getStatus() == PlayerStatus.ACTIVE) {
                currentIndex = index;
                return;
            }
        }
    }
}