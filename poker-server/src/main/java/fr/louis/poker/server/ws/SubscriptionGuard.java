package fr.louis.poker.server.ws;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Filtre les abonnements (trames STOMP SUBSCRIBE) : seules trois familles de destinations sont permises.
 *
 * - /topic/...      : les diffusions publiques (lobby, tables, déroulement public des parties) ;
 * - /user/queue/... : SES messages privés. Spring les traduit en une file propre à SA connexion ;
 * - /app/...        : les méthodes @SubscribeMapping (ex : /app/me). Traitées par nos contrôleurs,
 *                     jamais par le broker : elles ne donnent accès à aucune file.
 *
 * Tout le reste est refusé, en particulier /queue/... en direct. Spring range les messages privés
 * d'un joueur dans une file interne du type "/queue/game-user<identifiant de session>" :
 * sans ce filtre, un client qui devinerait cet identifiant pourrait s'y abonner
 * et recevoir les cartes d'un autre joueur.
 */
@Component
public class SubscriptionGuard implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null && StompCommand.SUBSCRIBE.equals(accessor.getCommand())
                && !isAllowed(accessor.getDestination())) {
            throw new MessageDeliveryException("Abonnement refusé : " + accessor.getDestination());
        }
        return message;
    }

    /** Vrai si un client a le droit de s'abonner à cette destination. */
    static boolean isAllowed(String destination) {
        return destination != null
                && (destination.startsWith("/topic/")
                || destination.startsWith("/user/queue/")
                || destination.startsWith("/app/"));
    }
}