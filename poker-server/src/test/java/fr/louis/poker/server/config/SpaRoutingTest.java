package fr.louis.poker.server.config;

import fr.louis.poker.server.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Vérifie que Spring sert correctement l'application Vue.
 * Utilise un index.html de test (src/test/resources/static/index.html),
 * pour ne pas dépendre de la compilation du front.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SpaRoutingTest {

    /** Texte présent uniquement dans l'index.html de test */
    private static final String INDEX_MARKER = "poker-spa-test";

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "{0} renvoie index.html")
    @ValueSource(strings = {"/login", "/leaderboard", "/tables/3"})
    @DisplayName("Les pages de l'application renvoient index.html, sans authentification")
    void appPagesServeIndex(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(INDEX_MARKER)));
    }

    @Test
    @DisplayName("Une route inconnue de l'API renvoie 401 sans jeton, et jamais index.html")
    void unknownApiRouteIsNotTheApp() throws Exception {
        mockMvc.perform(get("/api/n-existe-pas"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Un fichier manquant renvoie une vraie 404, et non index.html")
    void missingFileIs404() throws Exception {
        mockMvc.perform(get("/assets/n-existe-pas.js"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Les routes publiques de l'API restent accessibles")
    void publicApiStillWorks() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ok")));
    }

    @Test
    @DisplayName("Les routes privées de l'API exigent toujours un jeton")
    void privateApiStillProtected() throws Exception {
        mockMvc.perform(get("/api/leaderboard"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("La racine est transférée vers index.html (page d'accueil de Spring Boot)")
    void rootForwardsToIndex() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }
}