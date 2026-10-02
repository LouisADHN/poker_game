package fr.louis.poker.server.game;

import fr.louis.poker.server.lobby.TableView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tables")
public class GameController {

    private final GameService gameService;

    public GameController(GameService gameService) {
        this.gameService = gameService;
    }

    @PostMapping("/{id}/start")
    public TableView start(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return gameService.start(id, Long.valueOf(jwt.getSubject()));
    }
}
