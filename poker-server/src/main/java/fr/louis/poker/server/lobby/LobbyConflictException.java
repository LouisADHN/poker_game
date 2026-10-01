package fr.louis.poker.server.lobby;

public class LobbyConflictException extends RuntimeException {
    public LobbyConflictException(String message) {
        super(message);
    }
}
