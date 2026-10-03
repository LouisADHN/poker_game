package fr.louis.poker.server.stats;

import jakarta.persistence.*;

@Entity
@Table(name = "game_players")
public class GamePlayerRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id")
    private GameRecord game;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "position")
    private int position;

    @Column(name = "hands_played")
    private int handsPlayed;

    @Column(name = "hands_won")
    private int handsWon;

    protected GamePlayerRecord() {}

    public GamePlayerRecord(GameRecord game, Long userId, int position, int handsPlayed, int handsWon) {
        this.game = game;
        this.userId = userId;
        this.position = position;
        this.handsPlayed = handsPlayed;
        this.handsWon = handsWon;
    }

    public Long getId() {
        return id;
    }

    public GameRecord getGame() {
        return game;
    }

    public Long getUserId() {
        return userId;
    }

    public int getPosition() {
        return position;
    }

    public int getHandsPlayed() {
        return handsPlayed;
    }

    public int getHandsWon() {
        return handsWon;
    }
}
