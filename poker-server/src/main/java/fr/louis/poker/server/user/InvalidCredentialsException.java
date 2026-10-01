package fr.louis.poker.server.user;

public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Pseudo ou mot de passe incorrect");
    }
}
