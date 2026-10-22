package fr.louis.poker.server.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de la règle des abonnements autorisés.
 */
class SubscriptionGuardTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/topic/lobby",
            "/topic/tables/7",
            "/topic/tables/7/game",
            "/user/queue/game",
            "/app/me",                  // une méthode @SubscribeMapping
    })
    @DisplayName("Les destinations publiques, SES messages privés et les @SubscribeMapping sont autorisés")
    void allowed(String destination) {
        assertTrue(SubscriptionGuard.isAllowed(destination));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/queue/game-user3f2a9c",   // la file interne des messages privés d'une connexion
            "/queue/game",
            "/topic",                   // sans le "/" final : pas une destination de /topic/
            "/user/2/queue/game",       // la file d'un AUTRE utilisateur, désigné par son identifiant
            "",
    })
    @DisplayName("Toute autre destination est refusée, en particulier les files privées en direct")
    void refused(String destination) {
        assertFalse(SubscriptionGuard.isAllowed(destination));
    }

    @Test
    @DisplayName("Un abonnement sans destination est refusé")
    void nullIsRefused() {
        assertFalse(SubscriptionGuard.isAllowed(null));
    }
}