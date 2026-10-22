package fr.louis.poker.server.game;

import fr.louis.poker.engine.Street;
import fr.louis.poker.model.PlayerStatus;

import java.util.List;
import java.util.Map;

/**
 * Photographie de la partie, telle que tout le monde à la table peut la voir.
 * Aucune carte privée ici : celles du joueur qui demande seront ajoutées à part.
 *
 * Record immuable : une fois construit, personne ne peut le modifier,
 * il peut donc passer d'un thread à l'autre sans précaution.
 *
 * @param handNumber numéro de la main en cours (0 avant la première)
 * @param dealer     pseudo du joueur au bouton, null avant la première main
 * @param street     tour d'enchères en cours, null avant la première main
 * @param board      cartes communes visibles, en notation courte ("Ah", "Td"…)
 * @param pot        total des jetons au pot
 * @param players    les joueurs, dans l'ordre des sièges
 */
public record TableSnapshot(int handNumber,
                            String dealer,
                            Street street,
                            List<String> board,
                            int pot,
                            List<PlayerSnapshot> players) {

    public TableSnapshot {
        board = List.copyOf(board);
        players = List.copyOf(players);
    }

    /**
     * Ce qu'on voit d'un joueur.
     *
     * @param lastAction    dernière action pendant ce tour d'enchères, au format de PLAYER_ACTED
     *                      ({"type": "RAISE", "total": 120}), ou null
     * @param revealedCards cartes montrées au showdown, ou null
     * @param category      catégorie de sa main au showdown ("FULL_HOUSE"…), ou null
     */
    public record PlayerSnapshot(String name,
                                 int chips,
                                 int streetBet,
                                 PlayerStatus status,
                                 Map<String, Object> lastAction,
                                 List<String> revealedCards,
                                 String category) {
    }
}