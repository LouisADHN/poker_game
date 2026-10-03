package fr.louis.poker.server.stats;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface GameRecordRepository extends JpaRepository<GameRecord, Long> {

    @Query("select distinct g from GameRecord g join fetch g.players")
    List<GameRecord> findAllWithPlayers();

}
