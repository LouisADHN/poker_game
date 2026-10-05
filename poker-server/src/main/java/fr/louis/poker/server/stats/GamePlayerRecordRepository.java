package fr.louis.poker.server.stats;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Requêtes de statistiques, écrites en SQL natif PostgreSQL.
 *
 * Les alias sont entre guillemets ("gamesPlayed") : sans eux, PostgreSQL les convertirait
 * en minuscules (gamesplayed), et ils ne correspondraient plus aux getters de PlayerStatsRow.
 */
public interface GamePlayerRecordRepository extends JpaRepository<GamePlayerRecord, Long> {

    /**
     * Classement général : uniquement les joueurs ayant au moins une partie.
     * Tri : victoires, puis taux de victoire, puis mains gagnées, puis pseudo (pour un ordre stable).
     */
    @Query(value = """
            SELECT u.username                                  AS "username",
                   COUNT(*)                                    AS "gamesPlayed",
                   COUNT(*) FILTER (WHERE gp.position = 1)     AS "wins",
                   SUM(gp.hands_played)                        AS "handsPlayed",
                   SUM(gp.hands_won)                           AS "handsWon"
            FROM game_players gp
            JOIN users u ON u.id = gp.user_id
            GROUP BY u.id, u.username
            ORDER BY COUNT(*) FILTER (WHERE gp.position = 1) DESC,
                     CAST(COUNT(*) FILTER (WHERE gp.position = 1) AS DOUBLE PRECISION) / COUNT(*) DESC,
                     SUM(gp.hands_won) DESC,
                     u.username
            LIMIT :limit
            """, nativeQuery = true)
    List<PlayerStatsRow> findLeaderboard(@Param("limit") int limit);

    /**
     * Statistiques d'un joueur. Le LEFT JOIN garde le joueur même sans aucune partie :
     * les compteurs valent alors 0 (COUNT(gp.id) ignore les lignes vides, COALESCE remplace null par 0).
     * Résultat vide uniquement si l'utilisateur n'existe pas.
     */
    @Query(value = """
            SELECT u.username                                  AS "username",
                   COUNT(gp.id)                                AS "gamesPlayed",
                   COUNT(gp.id) FILTER (WHERE gp.position = 1) AS "wins",
                   COALESCE(SUM(gp.hands_played), 0)           AS "handsPlayed",
                   COALESCE(SUM(gp.hands_won), 0)              AS "handsWon"
            FROM users u
            LEFT JOIN game_players gp ON gp.user_id = u.id
            WHERE u.id = :userId
            GROUP BY u.id, u.username
            """, nativeQuery = true)
    Optional<PlayerStatsRow> findStatsByUserId(@Param("userId") Long userId);
}