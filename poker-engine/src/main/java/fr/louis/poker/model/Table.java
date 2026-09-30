package fr.louis.poker.model;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class Table {

    private static final int MAX_PLAYER = 10;

    /** Les joueurs dans l'ordre des sieges */
    private final List<Player> seats = new ArrayList<>();

    private final int smallBlind;
    private final int bigBlind;

    /** Siège du joueur qui a le bouton ; -1 tant qu'aucune main n'a été jouée. */
    private int dealerIndex = -1;

    public Table(int smallBlind, int bigBlind) {
        if (smallBlind <= 0 || smallBlind > bigBlind) {
            throw new IllegalArgumentException("Blindes invalides : " + smallBlind + "/" + bigBlind);
        }
        this.smallBlind = smallBlind;
        this.bigBlind = bigBlind;
    }

    public int getSmallBlind() {
        return smallBlind;
    }

    public int getBigBlind() {
        return bigBlind;
    }

    public void addPlayer(Player player) {
        if (seats.size() >= MAX_PLAYER) {
            throw new IllegalStateException("La table est pleine");
        }
        for (Player seat : seats) {
            if (player.getName().equals(seat.getName())) {
                throw new IllegalArgumentException("Un joueur possède déjà votre nom : " + player.getName());
            }
        }
        seats.add(player);
    }

    public List<Player> getPlayers() {
        return List.copyOf(seats);
    }

    // Joueurs dont le status n'est pas OUT, dans l'ordre des sièges
    public List<Player> getPlayersInHand() {
        return seats.stream().filter(p -> p.getStatus() != PlayerStatus.OUT).toList();
    }

    // Exception s'il reste moins de 2 joueurs en jeu
    // Avancer dealerIndex jusqu'au prochain siège non OUT
    public void moveButton() {
        if (getPlayersInHand().size() < 2) {
            throw new IllegalStateException("Il faut au moins 2 joueurs pour commencer");
        }
        for (int i = 1; i <= seats.size(); i++) {
            int index = (dealerIndex + i) % seats.size();
            if (seats.get(index).getStatus() != PlayerStatus.OUT) {
                dealerIndex = index;
                return;
            }
        }
    }

    // Exception si aucun bouton n'a encore été placé (dealerIndex == -1)
    public Player getDealer() {
        if (dealerIndex == -1) {
            throw new IllegalStateException("Le bouton n'a pas encore été placé");
        }
        return seats.get(dealerIndex);
    }

    public List<Player> playersAfter(Player player) {
        int start = seats.indexOf(player);
        if (start == -1) {
            throw new IllegalArgumentException(player + " n'est pas assis à cette table");
        }
        List<Player> result = new ArrayList<>();
        for (int i = 1; i <= seats.size(); i++) {
            Player p = seats.get((start + i) % seats.size());
            if (p.getStatus() != PlayerStatus.OUT) {
                result.add(p);
            }
        }
        return List.copyOf(result);
    }

}
