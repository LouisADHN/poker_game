package fr.louis.poker.server.auth;

import fr.louis.poker.server.security.TokenService;
import fr.louis.poker.server.user.UserAccount;
import fr.louis.poker.server.user.UserResponse;
import fr.louis.poker.server.user.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;
    private final TokenService tokenService;

    public AuthController(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(
            @Valid @RequestBody RegisterRequest request) {
        UserAccount user = userService.register(request.username(), request.password());
        return UserResponse.from(user);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        UserAccount user = userService.authenticate(request.username(), request.password());
        TokenService.IssuedToken token = tokenService.issue(user);
        return new LoginResponse(token.value(), token.expiresAt(), UserResponse.from(user));
    }
}
