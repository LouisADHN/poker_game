package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.server.lobby.LobbyConflictException;
import fr.louis.poker.server.lobby.LobbyService;
import fr.louis.poker.server.lobby.Seat;
import fr.louis.poker.server.lobby.TableView;
import fr.louis.poker.server.stats.GameResult;
import fr.louis.poker.server.stats.GameResultService;
import jakarta.annotation.PreDestroy;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

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
    private final GameResultService gameResultService;
    private final ApplicationEventPublisher events;

    public GameService(LobbyService lobbyService,
                       SimpMessagingTemplate messaging,
                       GameProperties properties,
                       GameResultService gameResultService,
                       ApplicationEventPublisher events) {
        this.lobbyService = lobbyService;
        this.messaging = messaging;
        this.properties = properties;
        this.gameResultService = gameResultService;
        this.events = events;
    }

    /** Lance la partie d'une table. Seul le créateur de la table peut le faire. */
    public TableView start(long tableId, Long userId) {
        // Vérifie les règles et passe la table en PLAYING (lève une exception sinon)
        TableView table = lobbyService.markPlaying(tableId, userId);

        GameSession session = new GameSession(
                tableId,
                table.name(),
                table.players(),
                table.settings(),
                properties,
                messaging,
                resultRecorderFor(table), // que faire du résultat si la partie va à son terme
                () -> finish(tableId));

        // Enregistrée AVANT le démarrage, pour accepter les actions dès le premier tour
        sessions.put(tableId, session);

        // Après le put (la session doit être trouvable), avant le start (rien n'est encore joué) :
        // le ConnectionRegistry signale les joueurs déjà déconnectés
        List<Long> userIds = table.players().stream().map(Seat::userId).toList();
        events.publishEvent(new GameStartedEvent(tableId, userIds));

        session.start();
        return table;
    }

    /**
     * Ce qu'il faut faire du résultat d'une partie terminée.
     *
     * Une partie du lobby est enregistrée : elle compte dans le classement.
     * Une partie locale ne l'est jamais. C'est un choix (le mode local est un jeu de salon),
     * et aussi une nécessité : un invité n'existe pas en base, et la table game_players
     * exige un user_id présent dans users (clé étrangère). L'enregistrer échouerait.
     */
    Consumer<GameResult> resultRecorderFor(TableView table) {
        if (table.local()) {
            return result -> { }; // ne rien faire
        }
        return gameResultService::record;
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

    /**
     * Un joueur quitte la partie en cours, définitivement.
     * Il passe en jeu automatique jusqu'à la fin, et il est libéré dans le lobby :
     * il peut rejoindre ou créer une autre table sans attendre la fin de cette partie.
     *
     * Quitter deux fois ne pose pas de problème : la seconde fois, rien ne change.
     */
    public void leaveGame(long tableId, Long userId) {
        GameSession session = sessions.get(tableId);
        if (session == null) {
            throw new LobbyConflictException("Aucune partie en cours à cette table");
        }
        if (session.presenceOf(userId) == null) {
            throw new LobbyConflictException("Tu ne joues pas à cette table");
        }

        session.updatePresence(userId, Presence.LEFT);
        lobbyService.releasePlayer(tableId, userId);
    }

    /**
     * Envoie à un joueur l'état complet de la partie de sa table (message privé GAME_STATE).
     * Appelé quand son navigateur (re)charge la page ou se reconnecte.
     */
    public void sendStateTo(long tableId, Long userId) {
        GameSession session = sessions.get(tableId);
        if (session == null) {
            throw new LobbyConflictException("Aucune partie en cours à cette table");
        }
        session.sendStateTo(userId);
    }

    /**
     * Change la présence d'un joueur dans la partie où il joue, s'il en a une.
     * Appelé par le ConnectionRegistry (connexion, déconnexion, fin du délai de grâce).
     *
     * Le lobby garantit qu'un joueur n'est assis qu'à une table : au plus une session le connaît,
     * les autres ignorent l'appel (updatePresence renvoie false pour un joueur inconnu).
     */
    public void updatePresence(Long userId, Presence presence) {
        sessions.values().forEach(session -> session.updatePresence(userId, presence));
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