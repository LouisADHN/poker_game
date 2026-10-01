package fr.louis.poker.server.auth;

import fr.louis.poker.server.TestcontainersConfiguration;
import fr.louis.poker.server.user.UserAccount;
import fr.louis.poker.server.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests de l'inscription de bout en bout : HTTP -> sécurité -> validation -> service -> base.
 * Chaque test est annulé à la fin grâce à @Transactional.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Envoie une demande d'inscription avec le JSON donné. */
    private ResultActions register(String json) throws Exception {
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private ResultActions register(String username, String password) throws Exception {
        return register("""
                {"username": "%s", "password": "%s"}
                """.formatted(username, password));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("Une inscription valide renvoie 201 avec l'id et le pseudo, sans le mot de passe")
    void validRegistration() throws Exception {
        register("Louis", "motdepasse123")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("Louis"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("Le mot de passe est stocké haché avec BCrypt, jamais en clair")
    void passwordIsHashed() throws Exception {
        register("Louis", "motdepasse123").andExpect(status().isCreated());

        UserAccount user = userRepository.findByUsernameIgnoreCase("Louis").orElseThrow();
        assertNotEquals("motdepasse123", user.getPasswordHash());
        assertTrue(user.getPasswordHash().startsWith("$2")); // préfixe des hashs BCrypt
        assertTrue(passwordEncoder.matches("motdepasse123", user.getPasswordHash()));
    }

    @Test
    @DisplayName("Un pseudo déjà pris, même avec une autre casse, renvoie 409")
    void duplicateUsernameIsRejected() throws Exception {
        register("Louis", "motdepasse123").andExpect(status().isCreated());

        register("louis", "autremotdepasse")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Pseudo indisponible"));
    }

    @Test
    @DisplayName("Un pseudo et un mot de passe trop courts renvoient 400, avec une erreur par champ")
    void tooShortFieldsAreRejected() throws Exception {
        register("ab", "court")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Requête invalide"))
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("Un pseudo avec des caractères interdits renvoie 400")
    void invalidCharactersAreRejected() throws Exception {
        register("Lou is!", "motdepasse123")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists());
    }

    @Test
    @DisplayName("Un mot de passe de plus de 72 caractères renvoie 400")
    void tooLongPasswordIsRejected() throws Exception {
        register("Louis", "a".repeat(73))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("Des champs manquants renvoient 400")
    void missingFieldsAreRejected() throws Exception {
        register("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("Une inscription refusée n'enregistre rien en base")
    void rejectedRegistrationSavesNothing() throws Exception {
        register("ab", "court").andExpect(status().isBadRequest());
        assertEquals(0, userRepository.count());
    }

    @Test
    @DisplayName("Une route non publique est refusée sans authentification")
    void protectedRouteRequiresAuthentication() throws Exception {
        // 403 pour l'instant ; deviendra 401 avec le JWT à l'étape 6b-3
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().is4xxClientError());
    }
}