package fr.louis.poker.server.user;

public class UsernameAlreadyTakenException extends RuntimeException{

    public UsernameAlreadyTakenException(String username) {
        super("Le pseudo « " + username + " » est déjà pris");
    }
}
