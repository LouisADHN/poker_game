package fr.louis.poker.server.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configuration du WebSocket avec le protocole STOMP.
 *
 * Destinations :
 * - /app/...         : messages envoyés au serveur (méthodes @MessageMapping / @SubscribeMapping)
 * - /topic/...       : diffusion à tous les abonnés (ex : l'état d'une table)
 * - /user/queue/...  : messages privés, délivrés à un seul utilisateur (ex : ses cartes)
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtStompInterceptor jwtStompInterceptor;

    public WebSocketConfig(JwtStompInterceptor jwtStompInterceptor) {
        this.jwtStompInterceptor = jwtStompInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Adresse d'ouverture de la connexion : ws://localhost:8080/ws
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Broker en mémoire, suffisant pour un seul serveur
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Chaque message entrant passe d'abord par l'intercepteur d'authentification
        registration.interceptors(jwtStompInterceptor);
    }
}