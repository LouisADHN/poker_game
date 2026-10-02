package fr.louis.poker.server.game;

import fr.louis.poker.server.lobby.LobbyConflictException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

@Controller
public class GameWsController {

    private final GameService gameService;
    private final SimpMessagingTemplate template;

    public GameWsController(GameService gameService, SimpMessagingTemplate template) {
        this.gameService = gameService;
        this.template = template;
    }

    @MessageMapping("/tables/{tableId}/action")
    public void action(@DestinationVariable long tableId, ActionMessage message, Principal principal) {
        Long userId = Long.valueOf(principal.getName());
        try {
            boolean accepted = gameService.submitAction(tableId, userId, message.toAction());
            if (!accepted) {
                sendError(principal, "Ce n'est pas ton tour");
            }
        } catch (IllegalArgumentException | LobbyConflictException e) {
            sendError(principal, e.getMessage());
        }
    }

    private void sendError(Principal principal, String text) {
        template.convertAndSendToUser(principal.getName(), GameSession.PRIVATE_QUEUE,
                new GameMessage("ERROR", Map.of("message", text)));
    }
}
