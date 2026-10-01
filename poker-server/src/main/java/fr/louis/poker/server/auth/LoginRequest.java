package fr.louis.poker.server.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Le pseudo est obligatoire") String username,
        @NotBlank(message = "Le mot de passe est obligatoire") String password) {

}
