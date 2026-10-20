package fr.louis.poker.server.game;

import fr.louis.poker.server.lobby.TableView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

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

    /** Quitter la partie en cours (bouton « Quitter la partie »). 204 si c'est fait, 409 sinon. */
    @PostMapping("/{id}/leave-game")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveGame(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        gameService.leaveGame(id, Long.valueOf(jwt.getSubject()));
    }
}
