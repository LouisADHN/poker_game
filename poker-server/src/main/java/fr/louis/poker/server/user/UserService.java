package fr.louis.poker.server.user;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    private final String dummyHash;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.dummyHash = passwordEncoder.encode("dummy-password");
    }

    // Elle retire les espaces autour du pseudo avec trim()
    // lève l'exception si existsByUsernameIgnoreCase renvoie vrai
    // puis enregistre un new UserAccount(username, passwordEncoder.encode(rawPassword)).
    @Transactional
    public UserAccount register(String username, String rawPassword) {
        String trimmed = username.trim();
        if (userRepository.existsByUsernameIgnoreCase(trimmed)) {
            throw new UsernameAlreadyTakenException(trimmed);
        }
        try {
            return userRepository.saveAndFlush(new UserAccount(trimmed, passwordEncoder.encode(rawPassword)));
        } catch (DataIntegrityViolationException e) {
            // Inscription simultanée du même pseudo : c'est l'index unique qui l'a détectée
            throw new UsernameAlreadyTakenException(trimmed);
        }
    }


    @Transactional(readOnly = true)
    public UserAccount authenticate(String username, String rawPassword) {
        Optional<UserAccount> user = userRepository.findByUsernameIgnoreCase(username.trim());

        if (user.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(rawPassword, user.get().getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return user.get();
    }

    @Transactional(readOnly = true)
    public Optional<UserAccount> findById(Long id) {
        return userRepository.findById(id);
    }
}
