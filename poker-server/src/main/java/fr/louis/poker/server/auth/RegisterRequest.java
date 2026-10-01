package fr.louis.poker.server.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Le pseudo ne peut pas être vide")
        @Size(min = 3, max = 20, message = "Le pseudo doit faire entre 3 et 20 caractères")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Seules les lettres, chiffres, tirets et tirets bas sont autorisés")
        String username,
        @NotBlank(message = "Le mot de passe ne peut pas être vide")
        @Size(min = 8, max = 72, message = "Le mot de passe doit faire entre 8 et 72 caractères")
        String password) {


}
