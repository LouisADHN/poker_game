package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.controller.GameView;
import fr.louis.poker.controller.PlayerController;

import java.time.Duration;
import java.util.Optional;
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
 *
 * Jeu automatique : un joueur absent ou parti ne doit pas faire attendre la table.
 * En jeu automatique, decide() renvoie null tout de suite, et setAutoPlay(true)
 * réveille un decide() déjà en train d'attendre.
 */
public class RemotePlayerController implements PlayerController {

    /**
     * Ce que la file transporte : une action du joueur, ou un « réveil » vide.
     * Une LinkedBlockingQueue refuse null : Optional.empty() joue donc le rôle de « pas d'action, arrête d'attendre ».
     */
    private static final Optional<Action> WAKE_UP = Optional.empty();

    /**
     * Le tour en cours : la situation envoyée au joueur, et l'heure limite pour jouer.
     * L'heure vient de System.nanoTime() : une horloge qui ne fait qu'avancer,
     * même si l'heure du serveur est corrigée pendant le tour.
     */
    private record PendingTurn(GameView view, long deadlineNanos) {
    }

    private final Long userId;
    private final Consumer<GameMessage> privateSender;
    private final Duration timeout;

    private final BlockingQueue<Optional<Action>> actions = new LinkedBlockingQueue<>();

    /** Vrai uniquement pendant que le moteur attend la décision de ce joueur. */
    private volatile boolean waiting = false;

    /** Vrai si le joueur joue en automatique (absent ou parti). */
    private volatile boolean autoPlay = false;

    /** Le tour en cours, ou null si le moteur n'attend pas ce joueur. Lu par d'autres threads. */
    private volatile PendingTurn pending;

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

    public boolean isAutoPlay() {
        return autoPlay;
    }

    /**
     * Active ou désactive le jeu automatique. Appelé par un autre thread que celui de la table.
     *
     * Si on l'active pendant que le moteur attend ce joueur, l'attente s'arrête tout de suite :
     * la table n'a pas à patienter jusqu'à la fin du délai.
     */
    public void setAutoPlay(boolean enabled) {
        // L'ordre compte : on écrit autoPlay AVANT de lire waiting (voir decide()).
        autoPlay = enabled;
        if (enabled && waiting) {
            actions.offer(WAKE_UP);
        }
    }

    /** Appelé par le thread de la table : prévient le joueur, puis attend son action. */
    @Override
    public Action decide(GameView view) {
        // Une action (ou un réveil) reçue hors de son tour ne doit jamais être utilisée maintenant
        actions.clear();
        waiting = true;
        try {
            /*
             * Le piège : setAutoPlay(true) peut arriver juste avant que waiting passe à true.
             * Il voit alors waiting == false et ne réveille personne : sans ce test,
             * on attendrait tout le délai pour rien.
             * Ici on écrit waiting PUIS on lit autoPlay ; setAutoPlay écrit autoPlay PUIS lit waiting.
             * Les deux champs sont volatile : l'un des deux threads voit forcément l'écriture de l'autre.
             */
            if (autoPlay) {
                return null;
            }

            // Retenu AVANT l'envoi : un joueur qui revient juste après le trouvera
            pending = new PendingTurn(view, System.nanoTime() + timeout.toNanos());
            privateSender.accept(GameMessages.yourTurn(view, timeout, timeout));
            // Attend une action au maximum "timeout" ; null si le délai expire
            Optional<Action> received = actions.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return received == null ? null : received.orElse(null);
        } catch (InterruptedException e) {
            // La partie est arrêtée : on rétablit le signal d'interruption pour l'appelant
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pending = null;
            waiting = false;
        }
    }

    /**
     * Le message YOUR_TURN du tour en cours, avec le temps qu'il reste vraiment ;
     * vide si ce n'est pas le tour de ce joueur. Pour un joueur qui revient en plein tour.
     *
     * Appelé par un autre thread que celui de la table.
     */
    public Optional<GameMessage> pendingTurn() {
        // Une seule lecture du champ : le tour peut se terminer pendant qu'on construit le message,
        // on travaille donc sur une copie locale qui, elle, ne changera pas
        PendingTurn current = pending;
        if (current == null) {
            return Optional.empty();
        }
        long nanosLeft = Math.max(0, current.deadlineNanos() - System.nanoTime());
        return Optional.of(GameMessages.yourTurn(current.view(), Duration.ofNanos(nanosLeft), timeout));
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
        return actions.offer(Optional.of(action));
    }
}