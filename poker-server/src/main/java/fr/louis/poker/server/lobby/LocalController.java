package fr.louis.poker.server.lobby;

import fr.louis.poker.server.security.TokenService;
import fr.louis.poker.server.user.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * Les routes du mode local : une table sur un écran partagé (la télé), rejointe avec un code.
 *
 * - POST /api/local               : un compte crée la table locale et reçoit son code (rôle PLAYER) ;
 * - POST /api/local/{code}/join   : un compte s'y assoit (rôle PLAYER) ;
 * - POST /api/local/{code}/guest  : un invité s'y assoit avec un simple pseudo (aucun jeton exigé).
 *
 * Ensuite, tout se passe comme pour une table normale : /api/tables/{id}/start pour lancer,
 * WebSocket pour jouer.
 */
@RestController
@RequestMapping("/api/local")
public class LocalController {

    private final LobbyService lobbyService;
    private final TokenService tokenService;

    public LocalController(LobbyService lobbyService, TokenService tokenService) {
        this.lobbyService = lobbyService;
        this.tokenService = tokenService;
    }

    /** Crée une table locale. L'hôte la possède mais ne s'y assoit pas. 201 avec la table et son code. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocalTable create(@AuthenticationPrincipal Jwt jwt,
                             @Valid @RequestBody CreateTableRequest request) {
        return lobbyService.createLocal(Long.valueOf(jwt.getSubject()), request);
    }

    /** Un compte s'assoit à la table locale de ce code. */
    @PostMapping("/{code}/join")
    public TableView join(@AuthenticationPrincipal Jwt jwt, @PathVariable String code) {
        return lobbyService.joinLocal(code, LobbyController.seatOf(jwt));
    }

    /**
     * Un invité s'assoit à la table locale de ce code, et reçoit son jeton d'invité.
     *
     * L'ordre compte : on l'assoit D'ABORD, on crée son jeton ENSUITE.
     * Si le code est faux, la table pleine ou le pseudo déjà pris, joinLocal lève une exception
     * et aucun jeton n'est distribué : pas d'invité fantôme qui pourrait se connecter sans être assis nulle part.
     */
    @PostMapping("/{code}/guest")
    public GuestJoinResponse joinAsGuest(@PathVariable String code,
                                         @Valid @RequestBody GuestRequest request) {
        long guestId = tokenService.newGuestId();
        TableView table = lobbyService.joinLocal(code, new Seat(guestId, request.username()));

        TokenService.IssuedToken token = tokenService.issueGuest(guestId, request.username());
        return new GuestJoinResponse(
                token.value(),
                token.expiresAt(),
                new UserResponse(guestId, request.username()),
                table);
    }
}