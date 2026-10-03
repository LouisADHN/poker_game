package fr.louis.poker.server.stats;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameResultService {

    private final GameRecordRepository gameRecordRepository;

    public GameResultService(GameRecordRepository gameRecordRepository) {
        this.gameRecordRepository = gameRecordRepository;
    }

    @Transactional
    public GameRecord record(GameResult result) {
        GameRecord game = new GameRecord(result.tableName(), result.startedAt(), result.handsPlayed());
        for (var player : result.players()) {
            game.addPlayer(player.userId(), player.position(), player.handsPlayed(), player.handsWon());
        }
        return gameRecordRepository.save(game);
    }
}
