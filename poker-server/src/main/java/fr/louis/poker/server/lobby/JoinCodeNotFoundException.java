package fr.louis.poker.server.lobby;

/** Aucune table locale en attente ne correspond à ce code. */
public class JoinCodeNotFoundException extends RuntimeException {

    public JoinCodeNotFoundException() {
        super("Aucune partie en attente avec ce code");
    }
}