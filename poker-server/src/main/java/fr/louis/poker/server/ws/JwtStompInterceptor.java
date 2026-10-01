package fr.louis.poker.server.ws;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;

/**
 * Authentifie chaque connexion WebSocket à partir du jeton JWT
 * présent dans l'en-tête "Authorization" de la trame STOMP CONNECT.
 *
 * Une fois l'utilisateur associé à la connexion, tous les messages suivants
 * de cette connexion lui sont attribués automatiquement par Spring.
 * Le nom de l'utilisateur (Principal.getName()) est le "subject" du jeton : son identifiant.
 */
@Component
public class JwtStompInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();

    public JwtStompInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        // Seule la trame CONNECT est vérifiée : c'est elle qui ouvre la session STOMP
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader(AUTHORIZATION);
            if (header == null || !header.startsWith(BEARER_PREFIX)) {
                throw new BadCredentialsException("Jeton d'authentification manquant");
            }

            try {
                Jwt jwt = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()));
                // Même type d'authentification que pour les requêtes HTTP
                accessor.setUser(authenticationConverter.convert(jwt));
            } catch (JwtException e) {
                // Signature invalide, jeton expiré ou mal formé
                throw new BadCredentialsException("Jeton d'authentification invalide", e);
            }
        }
        return message;
    }
}