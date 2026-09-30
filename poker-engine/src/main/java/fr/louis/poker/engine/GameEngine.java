package fr.louis.poker.engine;

import fr.louis.poker.action.Action;
import fr.louis.poker.controller.GameView;
import fr.louis.poker.controller.OpponentView;
import fr.louis.poker.controller.PlayerController;
import fr.louis.poker.evaluation.HandEvaluator;
import fr.louis.poker.evaluation.HandValue;
import fr.louis.poker.event.GameEvent;
import fr.louis.poker.event.GameListener;
import fr.louis.poker.model.Card;
import fr.louis.poker.model.Deck;
import fr.louis.poker.model.Player;
import fr.louis.poker.model.PlayerStatus;
import fr.louis.poker.model.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Orchestre les mains de Texas Hold'em à une table :
 * blinds, distribution, tours d'enchères, showdown et distribution des pots.
 */
public class GameEngine {

    private final Table table;
    private final Map<Player, PlayerController> controllers;
    private final Random random;
    private final List<GameListener> listeners = new ArrayList<>();

    private int handNumber = 0;

    // État de la main en cours (sert à construire les GameView)
    private PotManager potManager;
    private List<Card> board;
    private Street street;

    public GameEngine(Table table, Map<Player, PlayerController> controllers, Random random) {
        for (Player player : table.getPlayers()) {
            if (!controllers.containsKey(player)) {
                throw new IllegalArgumentException("Aucun contrôleur pour " + player);
            }
        }
        this.table = table;
        this.controllers = Map.copyOf(controllers);
        this.random = random;
    }

    public void addListener(GameListener listener) {
        listeners.add(listener);
    }

    public int getHandNumber() {
        return handNumber;
    }

    /** La partie est finie quand au plus un joueur a encore des jetons. */
    public boolean isGameOver() {
        return table.getPlayers().stream().filter(p -> p.getChips() > 0).count() <= 1;
    }

    // ------------------------------------------------------------------
    // Une main complète

    public void playHand() {
        // 1. Préparation : réinitialiser AVANT de bouger le bouton (les joueurs à 0 passent OUT)
        for (Player player : table.getPlayers()) {
            player.resetForNewHand();
        }
        table.moveButton();
        handNumber++;
        int chipsBefore = totalChips();

        Deck deck = new Deck();
        deck.shuffle(random);
        potManager = new PotManager();
        board = new ArrayList<>();

        // 2. Positions
        Player dealer = table.getDealer();
        List<Player> postflopOrder = table.playersAfter(dealer); // [..., dealer]
        Player smallBlind;
        Player bigBlind;
        List<Player> preflopOrder;

        if (postflopOrder.size() == 2) {
            // Tête-à-tête : le dealer est small blind et parle en premier preflop
            smallBlind = dealer;
            bigBlind = postflopOrder.getFirst();
            preflopOrder = List.of(smallBlind, bigBlind);
        } else {
            smallBlind = postflopOrder.get(0);
            bigBlind = postflopOrder.get(1);
            preflopOrder = rotate(postflopOrder, 2); // commence après la big blind
        }

        emit(new GameEvent.HandStarted(handNumber, dealer.getName()));

        // 4. Les quatre tours
        for (Street s : Street.values()) {
            street = s;

            if (s != Street.PREFLOP) {
                for (int i = 0; i < s.getCardsToDeal(); i++) {
                    board.add(deck.draw());
                }
                emit(new GameEvent.BoardDealt(s, board));
            }

            List<Player> order = (s == Street.PREFLOP) ? preflopOrder : postflopOrder;
            BettingRound round = new BettingRound(order, potManager, table.getBigBlind());

            if (s == Street.PREFLOP) {
                postBlind(round, smallBlind, table.getSmallBlind());
                postBlind(round, bigBlind, table.getBigBlind());
                dealHoleCards(deck, postflopOrder);
            }

            playBettingRound(round);

            // Tout le monde s'est couché sauf un : la main s'arrête là
            if (playersStillInHand().size() == 1) {
                break;
            }
        }

        // 5. Showdown (seulement s'il reste plusieurs joueurs)
        List<Player> contenders = playersStillInHand();
        Map<Player, HandValue> hands = new HashMap<>();
        if (contenders.size() > 1) {
            for (Player player : contenders) {
                List<Card> cards = new ArrayList<>(player.getHoleCards());
                cards.addAll(board);
                HandValue value = HandEvaluator.evaluate(cards);
                hands.put(player, value);
                emit(new GameEvent.HandRevealed(player.getName(), player.getHoleCards(), value));
            }
        }

        // 6. Distribution des pots
        Map<Player, Integer> winnings = potManager.distribute(hands);
        winnings.forEach((player, amount) -> {
            player.win(amount);
            emit(new GameEvent.PotWon(player.getName(), amount));
        });

        // 7. Garde-fou : aucun jeton ne doit apparaître ni disparaître
        if (totalChips() != chipsBefore) {
            throw new IllegalStateException("Incohérence : " + chipsBefore
                    + " jetons avant la main, " + totalChips() + " après");
        }

        emit(new GameEvent.HandEnded(handNumber));
    }

    // ------------------------------------------------------------------
    // Tour d'enchères

    private void playBettingRound(BettingRound round) {
        while (!round.isComplete()) {
            Player player = round.currentPlayer();
            Action action = askAndApply(player, round);
            emit(new GameEvent.PlayerActed(player.getName(), action,
                    round.getStreetBet(player), player.getChips()));
        }
    }

    /**
     * Demande une action au contrôleur et l'applique.
     * Une action absente ou illégale est remplacée par Check si possible, sinon Fold
     * (comme un joueur qui ne répond pas à temps sur un site de poker).
     */
    private Action askAndApply(Player player, BettingRound round) {
        GameView view = buildView(player, round);
        Action action = controllers.get(player).decide(view);

        if (action != null) {
            try {
                round.apply(action);
                return action;
            } catch (IllegalArgumentException e) {
                // Action illégale : on bascule sur l'action par défaut ci-dessous
            }
        }

        Action fallback = view.legalActions().canCheck() ? new Action.Check() : new Action.Fold();
        round.apply(fallback);
        return fallback;
    }

    private void postBlind(BettingRound round, Player player, int amount) {
        int paid = Math.min(amount, player.getChips());
        round.postBlind(player, amount);
        emit(new GameEvent.BlindPosted(player.getName(), paid));
    }

    /** Deux tours de table, une carte à la fois, en commençant après le dealer. */
    private void dealHoleCards(Deck deck, List<Player> players) {
        for (int round = 0; round < 2; round++) {
            for (Player player : players) {
                player.receiveCard(deck.draw());
            }
        }
        for (Player player : players) {
            emit(new GameEvent.HoleCardsDealt(player.getName(), player.getHoleCards()));
        }
    }

    // ------------------------------------------------------------------
    // Vue d'un joueur

    private GameView buildView(Player player, BettingRound round) {
        List<OpponentView> opponents = table.getPlayersInHand().stream()
                .filter(p -> p != player)
                .map(p -> new OpponentView(p.getName(), p.getChips(), p.getStatus(), round.getStreetBet(p)))
                .toList();

        return new GameView(
                player.getName(),
                player.getHoleCards(),
                player.getChips(),
                street,
                board,
                potManager.getTotal(),
                round.getCurrentBet(),
                round.getStreetBet(player),
                opponents,
                round.legalActions()
        );
    }

    // ------------------------------------------------------------------
    // Utilitaires

    /** Joueurs encore en course pour le pot (ni couchés, ni éliminés). */
    private List<Player> playersStillInHand() {
        return table.getPlayersInHand().stream()
                .filter(p -> p.getStatus() == PlayerStatus.ACTIVE || p.getStatus() == PlayerStatus.ALL_IN)
                .toList();
    }

    private int totalChips() {
        return table.getPlayers().stream().mapToInt(Player::getChips).sum();
    }

    /** Fait tourner une liste : rotate([A, B, C, D], 2) = [C, D, A, B]. */
    private static List<Player> rotate(List<Player> players, int offset) {
        List<Player> result = new ArrayList<>(players.subList(offset, players.size()));
        result.addAll(players.subList(0, offset));
        return result;
    }

    private void emit(GameEvent event) {
        for (GameListener listener : listeners) {
            listener.onEvent(event);
        }
    }
}