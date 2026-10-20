package fr.louis.poker.server.game;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * Suit les connexions WebSocket de chaque joueur, et en déduit sa présence.
 *
 * - Première connexion ouverte : PRESENT.
 * - Dernière connexion fermée : DISCONNECTED, et un délai de grâce commence.
 * - Délai écoulé sans reconnexion : AWAY (le joueur passe en jeu automatique).
 *
 * Un joueur peut avoir plusieurs connexions en même temps (plusieurs onglets, ou un rechargement
 * de page : la nouvelle connexion peut s'ouvrir AVANT que l'ancienne soit fermée).
 * On garde donc l'ensemble de ses sessions, et pas un simple booléen « connecté ».
 *
 * Concurrence : les événements arrivent sur des threads de Spring, la fin des délais sur le thread
 * du minuteur. Toutes les méthodes qui touchent l'état sont synchronized.
 * Ordre des verrous : ce registre, PUIS la GameSession (via presenceUpdater). Jamais l'inverse,
 * car une GameSession n'appelle jamais ce registre : pas d'interblocage possible.
 */
@Component
public class ConnectionRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    /** Ce qu'on fait d'un changement de présence : en pratique, GameService.updatePresence. */
    private final BiConsumer<Long, Presence> presenceUpdater;
    private final Duration reconnectGrace;

    /** Un seul thread suffit : la fin d'un délai de grâce est une tâche très courte. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("reconnect-grace").daemon().factory());

    /** Connexions ouvertes de chaque joueur : userId -> identifiants de session WebSocket. */
    private final Map<Long, Set<String>> sessionsByUser = new HashMap<>();

    /**
     * Délai de grâce en cours, par joueur, identifié par un « jeton » (un simple objet).
     * Quand le délai se termine, il ne fait quelque chose que si son jeton est toujours celui du joueur :
     * un délai annulé (le joueur est revenu) ou remplacé (il est reparti) ne fait plus rien.
     */
    private final Map<Long, Object> graceTokens = new HashMap<>();

    /** Constructeur utilisé par Spring. */
    @Autowired
    public ConnectionRegistry(GameService gameService, GameProperties properties) {
        this(gameService::updatePresence, properties.reconnectGrace());
    }

    /** Constructeur des tests : on y passe un faux presenceUpdater et un délai court. */
    ConnectionRegistry(BiConsumer<Long, Presence> presenceUpdater, Duration reconnectGrace) {
        this.presenceUpdater = presenceUpdater;
        this.reconnectGrace = reconnectGrace;
    }

    // ------------------------------------------------------------------
    // Événements de Spring

    /** Une connexion STOMP vient d'être acceptée (après la vérification du jeton). */
    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        Long userId = userIdOf(event.getUser());
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (userId != null && sessionId != null) {
            connected(userId, sessionId);
        }
    }

    /** Une connexion s'est fermée : onglet fermé, page rechargée, réseau coupé… */
    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        Long userId = userIdOf(event.getUser());
        if (userId != null) {
            disconnected(userId, event.getSessionId());
        }
    }

    // ------------------------------------------------------------------
    // Logique (package-private : testée directement, sans fabriquer d'événements Spring)

    synchronized void connected(Long userId, String sessionId) {
        Set<String> sessions = sessionsByUser.computeIfAbsent(userId, id -> new HashSet<>());
        boolean wasOffline = sessions.isEmpty();
        sessions.add(sessionId);

        if (wasOffline) {
            // Le délai en cours, s'il y en a un, ne fera plus rien : son jeton n'est plus enregistré
            graceTokens.remove(userId);
            presenceUpdater.accept(userId, Presence.PRESENT);
        }
    }

    synchronized void disconnected(Long userId, String sessionId) {
        Set<String> sessions = sessionsByUser.get(userId);
        // Spring peut publier deux fois la déconnexion d'une même session : la seconde est ignorée
        if (sessions == null || !sessions.remove(sessionId)) {
            return;
        }
        if (!sessions.isEmpty()) {
            return; // encore connecté ailleurs (un autre onglet)
        }

        sessionsByUser.remove(userId);
        log.info("Joueur {} déconnecté : {} pour revenir", userId, reconnectGrace);
        presenceUpdater.accept(userId, Presence.DISCONNECTED);

        Object token = new Object();
        graceTokens.put(userId, token);
        scheduler.schedule(() -> graceExpired(userId, token), reconnectGrace.toMillis(), TimeUnit.MILLISECONDS);
    }

    synchronized boolean isConnected(Long userId) {
        return sessionsByUser.containsKey(userId);
    }

    /** Appelé par le thread du minuteur, à la fin d'un délai de grâce. */
    private synchronized void graceExpired(Long userId, Object token) {
        // remove(clé, valeur) ne retire que si le joueur a TOUJOURS ce jeton-là
        if (!graceTokens.remove(userId, token)) {
            return;
        }
        log.info("Joueur {} absent depuis {} : jeu automatique", userId, reconnectGrace);
        try {
            presenceUpdater.accept(userId, Presence.AWAY);
        } catch (RuntimeException e) {
            // Sans ce try, l'exception serait avalée en silence par le ScheduledExecutorService
            log.error("Impossible de passer le joueur {} en absent", userId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** Le Principal d'une connexion porte l'identifiant du joueur (le subject du jeton), en texte. */
    private static Long userIdOf(Principal user) {
        if (user == null) {
            return null;
        }
        try {
            return Long.valueOf(user.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}