package fr.louis.poker.server.stats;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "games")
public class GameRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "table_name")
    private String tableName;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "hands_played")
    private int handsPlayed;

    @Column(name = "ended_at", insertable = false, updatable = false)
    private Instant endedAt;

    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL)
    List<GamePlayerRecord> players = new ArrayList<>();

    protected GameRecord() {}

    protected GameRecord(String tableName, Instant startedAt, int handsPlayed) {
        this.tableName = tableName;
        this.startedAt = startedAt;
        this.handsPlayed = handsPlayed;
    }

    public void addPlayer(Long userId, int position, int handsPlayed, int handsWon) {
        players.add(new GamePlayerRecord(this, userId, position, handsPlayed, handsWon));
    }

    public Long getId() {
        return id;
    }

    public String getTableName() {
        return tableName;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public int getHandsPlayed() {
        return handsPlayed;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public List<GamePlayerRecord> getPlayers() {
        return List.copyOf(players);
    }
}
