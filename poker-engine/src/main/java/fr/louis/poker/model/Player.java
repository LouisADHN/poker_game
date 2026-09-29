package fr.louis.poker.model;

import java.util.ArrayList;
import java.util.List;

public class Player {

    private final String name;
    private int chips;
    private final List<Card> holeCards;
    private PlayerStatus status;

    public Player(String name, int chips) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Veuillez écrire un nom correct");
        }
        if (chips < 0) {
            throw new IllegalArgumentException("Nombre de jetons négatifs");
        }
        this.name = name;
        this.chips = chips;
        this.holeCards = new ArrayList<>();
        this.status = PlayerStatus.ACTIVE;
    }

    public void receiveCard(Card card) {
        if (holeCards.size() == 2) {
            throw new IllegalStateException("Le joueur a déjà 2 cartes");
        }
        this.holeCards.add(card);
    }

    public List<Card> getHoleCards() {
        return List.copyOf(this.holeCards);
    }

    public void bet(int amount) {
        if (status != PlayerStatus.ACTIVE) {
            throw new IllegalStateException("Joueur plus actif");
        }
        if (amount <= 0 || amount > chips) {
            throw new IllegalArgumentException("Montant impossible à miser");
        }
        chips -= amount;
        if (chips == 0) {
            status = PlayerStatus.ALL_IN;
        }
    }

    public void fold() {
        if (status != PlayerStatus.ACTIVE) {
            throw new IllegalStateException("Joueur plus actif");
        }
        this.status = PlayerStatus.FOLDED;
    }

    public void win(int amount) {
        this.chips += amount;
    }

    public void resetForNewHand() {
        holeCards.clear();
        if(this.chips == 0) {
            this.status = PlayerStatus.OUT;
        } else {
            this.status = PlayerStatus.ACTIVE;
        }
    }

    public String getName() {
        return name;
    }

    public int getChips() {
        return chips;
    }

    public PlayerStatus getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return this.name + " (" + this.chips + ")";
    }
}
