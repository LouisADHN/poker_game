package fr.louis.poker.server.game;

import fr.louis.poker.event.GameEvent;
import fr.louis.poker.event.GameListener;
import fr.louis.poker.model.Player;
import fr.louis.poker.server.stats.GameResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Observe les événements d'une partie et tient les comptes nécessaires au résultat final :
 * mains jouées et gagnées par joueur, et ordre d'élimination.
 *
 * Classement final : le dernier joueur avec des jetons est 1er, puis le dernier éliminé,
 * et ainsi de suite. Deux joueurs éliminés dans la même main sont départagés
 * par leur tapis au début de cette main : le plus gros est mieux classé.
 *
 * Utilisé uniquement par le thread de la table : pas de problème de concurrence.
 */
class ResultTracker implements GameListener {

    private final List<Player> players;
    private final Map<String, Long> userIdByName;

    private final Map<String, Integer> handsPlayed = new HashMap<>();
    private final Map<String, Integer> handsWon = new HashMap<>();
    private final Set<String> wonThisHand = new HashSet<>();
    private final Map<String, Integer> chipsAtHandStart = new HashMap<>();

    /** Joueurs éliminés, du premier éliminé au dernier. */
    private final List<String> eliminationOrder = new ArrayList<>();

    private int handCount = 0;

    ResultTracker(List<Player> players, Map<String, Long> userIdByName) {
        this.players = List.copyOf(players);
        this.userIdByName = Map.copyOf(userIdByName);
    }

    @Override
    public void onEvent(GameEvent event) {
        switch (event) {
            case GameEvent.HandStarted started -> onHandStarted();
            case GameEvent.HoleCardsDealt(String player, var cards) -> handsPlayed.merge(player, 1, Integer::sum);
            case GameEvent.PotWon(String player, int amount) -> wonThisHand.add(player);
            case GameEvent.HandEnded ended -> onHandEnded();
            default -> {
                // Les autres événements n'influencent pas le résultat
            }
        }
    }

    private void onHandStarted() {
        handCount++;
        wonThisHand.clear();
        chipsAtHandStart.clear();
        for (Player player : players) {
            chipsAtHandStart.put(player.getName(), player.getChips());
        }
    }

    private void onHandEnded() {
        // Une main gagnée compte une seule fois, même si le joueur a remporté plusieurs pots
        for (String winner : wonThisHand) {
            handsWon.merge(winner, 1, Integer::sum);
        }

        // Les joueurs tombés à zéro pendant cette main, du moins bien classé au mieux classé
        players.stream()
                .filter(p -> p.getChips() == 0)
                .filter(p -> !eliminationOrder.contains(p.getName()))
                .sorted(Comparator
                        .comparingInt((Player p) -> chipsAtHandStart.getOrDefault(p.getName(), 0))
                        .thenComparing(Player::getName))
                .forEach(p -> eliminationOrder.add(p.getName()));
    }

    /** Construit le résultat final, à appeler quand la partie est terminée. */
    GameResult toResult(String tableName, Instant startedAt) {
        List<String> ranking = new ArrayList<>();

        // En tête : le ou les joueurs qui ont encore des jetons (normalement, le seul vainqueur)
        players.stream()
                .filter(p -> p.getChips() > 0)
                .sorted(Comparator.comparingInt(Player::getChips).reversed())
                .forEach(p -> ranking.add(p.getName()));

        // Puis les éliminés, du dernier éliminé au premier
        for (int i = eliminationOrder.size() - 1; i >= 0; i--) {
            ranking.add(eliminationOrder.get(i));
        }

        List<GameResult.PlayerResult> results = new ArrayList<>();
        for (int i = 0; i < ranking.size(); i++) {
            String name = ranking.get(i);
            results.add(new GameResult.PlayerResult(
                    userIdByName.get(name),
                    i + 1,
                    handsPlayed.getOrDefault(name, 0),
                    handsWon.getOrDefault(name, 0)));
        }
        return new GameResult(tableName, startedAt, handCount, results);
    }
}