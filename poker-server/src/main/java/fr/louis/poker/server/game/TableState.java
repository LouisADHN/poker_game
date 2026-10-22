package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.engine.Street;
import fr.louis.poker.event.GameEvent;
import fr.louis.poker.event.GameListener;
import fr.louis.poker.model.PlayerStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * L'état lisible d'une partie, tenu à jour à partir des événements du moteur.
 *
 * C'est une « projection » : comme le store game.ts du front, elle rejoue chaque événement
 * pour reconstruire l'état. Mais elle voit TOUS les événements depuis le début de la partie,
 * elle est donc toujours juste. Un joueur qui (re)charge sa page pourra lui demander une photographie.
 *
 * Pourquoi ne pas lire directement les objets du moteur (Player, board…) ?
 * Ils sont modifiés par le thread de la table sans aucune synchronisation :
 * les lire depuis un autre thread pourrait donner un état à moitié modifié.
 * Ici, tout passe par des méthodes synchronized, et snapshot() renvoie des copies immuables.
 *
 * Écrite par le thread de la table (onEvent), lue par d'autres threads (snapshot, holeCardsOf).
 */
class TableState implements GameListener {

    /** État modifiable d'un joueur. Toujours manipulé sous le verrou de TableState. */
    private static final class PlayerState {
        final String name;
        int chips;
        int streetBet;
        PlayerStatus status = PlayerStatus.ACTIVE;
        Map<String, Object> lastAction;
        List<String> revealedCards;
        String category;

        PlayerState(String name, int chips) {
            this.name = name;
            this.chips = chips;
        }
    }

    /** Les joueurs, par pseudo. LinkedHashMap : l'ordre d'insertion est l'ordre des sièges. */
    private final Map<String, PlayerState> players = new LinkedHashMap<>();

    /** Cartes privées de chaque joueur pour la main en cours. Jamais dans snapshot(). */
    private final Map<String, List<String>> holeCards = new HashMap<>();

    private int handNumber = 0;
    private String dealer;
    private Street street;
    private List<String> board = List.of();
    private int pot = 0;

    /**
     * @param playerNames   les pseudos, dans l'ordre des sièges
     * @param startingChips le tapis de départ de chacun
     */
    TableState(List<String> playerNames, int startingChips) {
        for (String name : playerNames) {
            players.put(name, new PlayerState(name, startingChips));
        }
    }

    @Override
    public synchronized void onEvent(GameEvent event) {
        switch (event) {
            case GameEvent.HandStarted(int number, String dealerName) -> {
                handNumber = number;
                dealer = dealerName;
                street = Street.PREFLOP;
                board = List.of();
                pot = 0;
                holeCards.clear();
                for (PlayerState p : players.values()) {
                    p.streetBet = 0;
                    p.lastAction = null;
                    p.revealedCards = null;
                    p.category = null;
                    // Même règle que Player.resetForNewHand() dans le moteur
                    p.status = p.chips > 0 ? PlayerStatus.ACTIVE : PlayerStatus.OUT;
                }
            }

            case GameEvent.BlindPosted(String player, int amount) -> {
                PlayerState p = players.get(player);
                p.chips -= amount;
                p.streetBet += amount;
                if (p.chips == 0) {
                    p.status = PlayerStatus.ALL_IN;
                }
                pot += amount;
            }

            case GameEvent.HoleCardsDealt(String player, var cards) ->
                    holeCards.put(player, GameMessages.cards(cards));

            case GameEvent.BoardDealt(Street newStreet, var cards) -> {
                street = newStreet;
                board = GameMessages.cards(cards);
                // Nouveau tour d'enchères : les mises du tour précédent sont dans le pot
                for (PlayerState p : players.values()) {
                    p.streetBet = 0;
                    p.lastAction = null;
                }
            }

            case GameEvent.PlayerActed(String player, var action, int streetBet, int chipsLeft) -> {
                PlayerState p = players.get(player);
                pot += p.chips - chipsLeft; // ce qu'il vient de mettre au pot
                p.chips = chipsLeft;
                p.streetBet = streetBet;
                p.lastAction = GameMessages.action(action);
                if (action instanceof Action.Fold) {
                    p.status = PlayerStatus.FOLDED;
                } else if (chipsLeft == 0) {
                    p.status = PlayerStatus.ALL_IN;
                }
            }

            case GameEvent.HandRevealed(String player, var cards, var value) -> {
                PlayerState p = players.get(player);
                p.revealedCards = GameMessages.cards(cards);
                p.category = value.category().name();
            }

            case GameEvent.PotWon(String player, int amount) -> {
                players.get(player).chips += amount;
                pot -= amount;
            }

            case GameEvent.HandEnded(int number) -> {
                // Rien à changer : la photographie montre la fin de la main jusqu'à la suivante
            }
        }
    }

    /** La photographie publique de la partie : ce que tout le monde à la table peut voir. */
    synchronized TableSnapshot snapshot() {
        List<TableSnapshot.PlayerSnapshot> seats = new ArrayList<>();
        for (PlayerState p : players.values()) {
            seats.add(new TableSnapshot.PlayerSnapshot(
                    p.name, p.chips, p.streetBet, p.status,
                    p.lastAction, p.revealedCards, p.category));
        }
        return new TableSnapshot(handNumber, dealer, street, board, pot, seats);
    }

    /** Les cartes privées d'un joueur pour la main en cours, ou une liste vide. */
    synchronized List<String> holeCardsOf(String player) {
        return holeCards.getOrDefault(player, List.of());
    }
}