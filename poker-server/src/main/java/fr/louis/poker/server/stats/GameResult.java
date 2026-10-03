package fr.louis.poker.server.stats;

import java.time.Instant;
import java.util.List;

/**
 * Le résultat d'une partie terminée, tel que produit par la session de jeu.
 * Simple record, sans lien avec la base : c'est GameResultService qui l'enregistre.
 *
 * @param players les joueurs, du premier (le vainqueur) au dernier
 */
public record GameResult(String tableName, Instant startedAt, int handsPlayed, List<PlayerResult> players) {

    public GameResult {
        players = List.copyOf(players);
    }

    /** Le résultat d'un joueur : sa place finale et son activité pendant la partie. */
    public record PlayerResult(Long userId, int position, int handsPlayed, int handsWon) {
    }
}