package fr.louis.poker.server.lobby;

public class TableNotFoundException extends RuntimeException {
    public TableNotFoundException(long id) {
        super("Table n°" + id + " introuvable");
    }
}
