package fr.louis.poker.server.stats;

public record PlayerStats(String username,
                          long gamesPlayed,
                          long wins,
                          long handsPlayed,
                          long handsWon,
                          double winRate) {

    public static PlayerStats from(PlayerStatsRow row) {
        double winRate = row.getGamesPlayed() == 0
                ? 0
                : (double) row.getWins() / row.getGamesPlayed();
        return new PlayerStats(row.getUsername(), row.getGamesPlayed(), row.getWins(),
                row.getHandsPlayed(), row.getHandsWon(), winRate);
    }
}
