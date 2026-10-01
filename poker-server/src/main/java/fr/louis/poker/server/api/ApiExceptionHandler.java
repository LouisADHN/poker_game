package fr.louis.poker.server.api;

import fr.louis.poker.server.user.InvalidCredentialsException;
import fr.louis.poker.server.user.UsernameAlreadyTakenException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transforme les exceptions en réponses HTTP propres,
 * au format standard "Problem Details" (RFC 9457).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Pseudo déjà pris : 409 Conflict. */
    @ExceptionHandler(UsernameAlreadyTakenException.class)
    ProblemDetail handleUsernameTaken(UsernameAlreadyTakenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Pseudo indisponible");
        return problem;
    }

    /** Données invalides (@Valid) : 400 Bad Request, avec le détail champ par champ. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Certains champs sont invalides");
        problem.setTitle("Requête invalide");

        // Un seul message par champ (le premier), pour un affichage simple côté client
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        problem.setProperty("errors", errors);

        return problem;
    }

    /** Pseudo ou mot de passe incorrect : 401 Unautorized */
    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail handleInvalidCredentials(InvalidCredentialsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setTitle("Identifiants invalides");
        return problem;
    }
}