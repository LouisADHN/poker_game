package fr.louis.poker.server.user;

import fr.louis.poker.server.TestcontainersConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test d'intégration : tourne contre une vraie base PostgreSQL
 * démarrée par Testcontainers (Docker doit être lancé).
 * Grâce à @Transactional, chaque test est annulé à la fin : la base reste vide.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /** Force l'écriture en base puis vide le cache, pour relire les vraies données. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("L'enregistrement génère un identifiant, et la base remplit la date de création")
    void saveGeneratesIdAndCreationDate() {
        UserAccount saved = userRepository.save(new UserAccount("Louis", "hash"));

        assertNotNull(saved.getId());
        assertNull(saved.getCreatedAt()); // pas encore relu depuis la base

        flushAndClear();

        UserAccount reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertEquals("Louis", reloaded.getUsername());
        assertEquals("hash", reloaded.getPasswordHash());
        assertNotNull(reloaded.getCreatedAt()); // rempli par DEFAULT now()
    }

    @Test
    @DisplayName("La recherche par pseudo ignore la casse")
    void findByUsernameIgnoresCase() {
        userRepository.save(new UserAccount("Louis", "hash"));
        flushAndClear();

        assertTrue(userRepository.findByUsernameIgnoreCase("louis").isPresent());
        assertTrue(userRepository.findByUsernameIgnoreCase("LOUIS").isPresent());
    }

    @Test
    @DisplayName("Un pseudo inconnu n'est pas trouvé")
    void unknownUsernameIsNotFound() {
        assertTrue(userRepository.findByUsernameIgnoreCase("personne").isEmpty());
        assertFalse(userRepository.existsByUsernameIgnoreCase("personne"));
    }

    @Test
    @DisplayName("existsByUsernameIgnoreCase détecte un pseudo déjà pris, quelle que soit la casse")
    void existsIgnoresCase() {
        userRepository.save(new UserAccount("Louis", "hash"));
        flushAndClear();

        assertTrue(userRepository.existsByUsernameIgnoreCase("lOuIs"));
    }

    @Test
    @DisplayName("La base refuse deux pseudos qui ne diffèrent que par la casse")
    void uniqueIndexRejectsCaseInsensitiveDuplicate() {
        userRepository.saveAndFlush(new UserAccount("Louis", "hash1"));

        assertThrows(DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(new UserAccount("louis", "hash2")));
    }
}