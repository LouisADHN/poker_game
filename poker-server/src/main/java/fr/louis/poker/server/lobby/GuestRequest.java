package fr.louis.poker.server.lobby;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Ce qu'envoie un invité pour s'asseoir à une table locale : juste un pseudo.
 *
 * Mêmes règles que le pseudo d'un compte (voir RegisterRequest) : le pseudo s'affiche
 * sur l'écran partagé et dans les messages de la partie, il doit rester lisible.
 */
public record GuestRequest(
        @NotBlank(message = "Le pseudo ne peut pas être vide")
        @Size(min = 3, max = 20, message = "Le pseudo doit faire entre 3 et 20 caractères")
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Seules les lettres, chiffres, tirets et tirets bas sont autorisés")
        String username) {
}