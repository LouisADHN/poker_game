package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.controller.GameView;
import fr.louis.poker.controller.PlayerController;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Contrôleur d'un joueur connecté à distance.
 *
 * Deux threads se rencontrent ici :
 * - le thread de la table appelle decide() et attend l'action du joueur ;
 * - un thread de Spring reçoit l'action par WebSocket et appelle submit().
 *
 * La BlockingQueue assure l'échange entre les deux sans problème de concurrence.
 * Si le joueur ne répond pas à temps, decide() renvoie null :
 * le moteur applique alors l'action par défaut (check si possible, sinon fold).
 */
public class RemotePlayerController implements PlayerController {

    private final Long userId;
    private final Consumer<GameMessage> privateSender;
    private final Duration timeout;

    private final BlockingQueue<Action> actions = new LinkedBlockingQueue<>();

    /** Vrai uniquement pendant que le moteur attend la décision de ce joueur. */
    private volatile boolean waiting = false;

    /**
     * @param userId        identifiant du joueur
     * @param privateSender envoie un message privé à ce joueur (en pratique : par WebSocket)
     * @param timeout       temps laissé au joueur pour agir
     */
    public RemotePlayerController(Long userId, Consumer<GameMessage> privateSender, Duration timeout) {
        this.userId = userId;
        this.privateSender = privateSender;
        this.timeout = timeout;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isWaiting() {
        return waiting;
    }

    /** Appelé par le thread de la table : prévient le joueur, puis attend son action. */
    @Override
    public Action decide(GameView view) {
        // Une action reçue hors de son tour ne doit jamais être utilisée maintenant
        actions.clear();
        waiting = true;
        try {
            privateSender.accept(GameMessages.yourTurn(view));
            // Attend une action au maximum "timeout" ; null si le délai expire
            return actions.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // La partie est arrêtée : on rétablit le signal d'interruption pour l'appelant
            Thread.currentThread().interrupt();
            return null;
        } finally {
            waiting = false;
        }
    }

    /**
     * Appelé par un thread de Spring quand le joueur envoie une action.
     *
     * @return false si ce n'est pas le tour du joueur (l'action est ignorée)
     */
    public boolean submit(Action action) {
        if (!waiting) {
            return false;
        }
        return actions.offer(action);
    }
}