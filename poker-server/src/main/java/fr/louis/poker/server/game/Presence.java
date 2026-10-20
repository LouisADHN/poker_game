package fr.louis.poker.server.game;

public enum Presence {
    // Connecté
    PRESENT,
    // Connexion perdue
    DISCONNECTED,
    // Absent depuis un moment, il joue en automatique
    AWAY,
    // A quitté la partie
    LEFT;

    public boolean isAutoPlay() {
        return this == AWAY || this == LEFT;
    }
}
