package fr.louis.poker.server.stats;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class StatsService {

    private static final int MAX_LEADERBOARD_SIZE = 100;

    private final GamePlayerRecordRepository gamePlayerRecordRepository;

    public StatsService(GamePlayerRecordRepository gamePlayerRecordRepository) {
        this.gamePlayerRecordRepository = gamePlayerRecordRepository;
    }

    @Transactional(readOnly = true)
    public List<PlayerStats> leaderboard(int limit) {
        int safeLimit = Math.clamp(limit, 1, MAX_LEADERBOARD_SIZE);
        return gamePlayerRecordRepository.findLeaderboard(safeLimit).stream()
                .map(PlayerStats::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PlayerStats statsFor(Long userId) {
        return gamePlayerRecordRepository.findStatsByUserId(userId)
                .map(PlayerStats::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable"));
    }
}
