package fr.louis.poker.server.lobby;

import fr.louis.poker.server.security.TokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tables")
public class LobbyController {

    private final LobbyService lobbyService;

    public LobbyController(LobbyService lobbyService) {
        this.lobbyService = lobbyService;
    }

    @GetMapping
    public List<TableView> list() {
        return lobbyService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TableView create(@AuthenticationPrincipal Jwt jwt,
                            @Valid
                            @RequestBody
                            CreateTableRequest request) {
        return lobbyService.create(seatOf(jwt), request);
    }

    @GetMapping("/{id}")
    public TableView get(@PathVariable long id) {
        return lobbyService.get(id);
    }

    @PostMapping("/{id}/join")
    public TableView join(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return lobbyService.join(id, seatOf(jwt));
    }

    @PostMapping("/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        lobbyService.leave(id, seatOf(jwt).userId());
    }


    private static Seat seatOf(Jwt jwt) {
        return new Seat(Long.valueOf(jwt.getSubject()), jwt.getClaimAsString(TokenService.USERNAME_CLAIM));
    }
}
