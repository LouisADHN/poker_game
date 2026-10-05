package fr.louis.poker.server.stats;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/api/leaderboard")
    public List<PlayerStats> leaderboard(@RequestParam(defaultValue = "20") int limit) {
        return statsService.leaderboard(limit);
    }

    @GetMapping("/api/users/me/stats")
    public PlayerStats myStats(@AuthenticationPrincipal Jwt jwt) {
        return statsService.statsFor(Long.valueOf(jwt.getSubject()));
    }
}
