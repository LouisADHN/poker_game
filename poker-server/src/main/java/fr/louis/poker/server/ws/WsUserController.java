package fr.louis.poker.server.ws;

import fr.louis.poker.server.user.UserResponse;
import fr.louis.poker.server.user.UserService;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class WsUserController {

    private final UserService userService;

    public WsUserController(UserService userService) {
        this.userService = userService;
    }

    @SubscribeMapping("/me")
    public UserResponse me(Principal principal) {
        Long id = Long.valueOf(principal.getName());
        return userService.findById(id)
                .map(UserResponse::from)
                .orElseThrow(() -> new IllegalStateException("L'utilisateur n'existe pas"));
    }
}
