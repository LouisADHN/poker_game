package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.server.lobby.LobbyConflictException;
import fr.louis.poker.server.lobby.LobbyService;
import fr.louis.poker.server.lobby.TableView;
import jakarta.annotation.PreDestroy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestre les parties : lancement depuis le lobby, transmission des actions,
 * et retour au lobby quand la partie se termine.
 *
 * Le lobby ne connaît rien du jeu : c'est ce service qui fait le lien entre les deux.
 */
@Service
public class GameService {

    /** Sessions en cours, par table. ConcurrentHashMap : utilisée par plusieurs threads. */
    private final Map<Long, GameSession> sessions = new ConcurrentHashMap<>();

    private final LobbyService lobbyService;
    private final SimpMessagingTemplate messaging;
    private final GameProperties properties;

    public GameService(LobbyService lobbyService, SimpMessagingTemplate messaging, GameProperties properties) {
        this.lobbyService = lobbyService;
        this.messaging = messaging;
        this.properties = properties;
    }

    /** Lance la partie d'une table. Seul le créateur de la table peut le faire. */
    public TableView start(long tableId, Long userId) {
        // Vérifie les règles et passe la table en PLAYING (lève une exception sinon)
        TableView table = lobbyService.markPlaying(tableId, userId);

        GameSession session = new GameSession(
                tableId,
                table.players(),
                table.settings(),
                properties,
                messaging,
                () -> finish(tableId));

        // Enregistrée AVANT le démarrage, pour accepter les actions dès le premier tour
        sessions.put(tableId, session);
        session.start();
        return table;
    }

    /**
     * Transmet l'action d'un joueur à la partie de sa table.
     *
     * @return false si ce n'est pas son tour
     */
    public boolean submitAction(long tableId, Long userId, Action action) {
        GameSession session = sessions.get(tableId);
        if (session == null) {
            throw new LobbyConflictException("Aucune partie en cours à cette table");
        }
        return session.submit(userId, action);
    }

    public boolean isRunning(long tableId) {
        return sessions.containsKey(tableId);
    }

    /** Appelé par la session quand la partie se termine. */
    private void finish(long tableId) {
        sessions.remove(tableId);
        lobbyService.endGame(tableId);
    }

    /** Arrêt du serveur : on arrête proprement toutes les parties en cours. */
    @PreDestroy
    public void stopAll() {
        sessions.values().forEach(GameSession::stop);
    }
}