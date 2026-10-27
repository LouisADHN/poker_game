package fr.louis.poker.server.lobby;

import fr.louis.poker.server.user.UserResponse;

import java.time.Instant;

/**
 * Réponse à un invité qui vient de s'asseoir à une table locale.
 *
 * Même forme que LoginResponse (jeton, expiration, utilisateur) : le client range ce jeton
 * exactement comme celui d'un compte. On y ajoute la table, pour afficher tout de suite la salle d'attente.
 *
 * @param user  l'invité, avec son identifiant NÉGATIF
 * @param table la table où il est assis
 */
public record GuestJoinResponse(String token, Instant expiresAt, UserResponse user, TableView table) {
}