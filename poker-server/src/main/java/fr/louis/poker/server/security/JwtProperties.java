package fr.louis.poker.server.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration des jetons JWT, lue depuis application.properties :
 * poker.jwt.secret     : clé secrète HMAC encodée en Base64 (au moins 32 octets)
 * poker.jwt.expiration : durée de validité d'un jeton (ex : 12h, 30m)
 */
@ConfigurationProperties(prefix = "poker.jwt")
public record JwtProperties(String secret, Duration expiration) {
}