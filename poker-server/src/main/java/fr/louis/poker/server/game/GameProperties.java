package fr.louis.poker.server.game;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Paramètres des parties, modifiables dans l'application.properties :
 * poker.game.turn-timeout          : temps laissé à un joueur pour agir (30s par défaut)
 * poker.game.pause-between-hands   : pause entre deux mains (3s par défaut)
 */
@ConfigurationProperties(prefix = "poker.game")
public record GameProperties(
        @DefaultValue("30s") Duration turnTimeout,
        @DefaultValue("3s") Duration pauseBetweenHands) {
}