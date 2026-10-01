package fr.louis.poker.server.auth;

import fr.louis.poker.server.user.UserResponse;

import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt, UserResponse user) {
}
