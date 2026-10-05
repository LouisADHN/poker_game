package fr.louis.poker.server.stats;

/**
 * Projection : une ligne de résultat des requêtes de statistiques.
 * Spring Data fournit l'implémentation : chaque getter correspond
 * à une colonne du résultat SQL, d'après son alias (AS "gamesPlayed"…).
 */
public interface PlayerStatsRow {

    String getUsername();

    long getGamesPlayed();

    long getWins();

    long getHandsPlayed();

    long getHandsWon();
}