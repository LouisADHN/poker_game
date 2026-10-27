package fr.louis.poker.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuration de la sécurité HTTP.
 *
 * - L'API (/api/**) exige un jeton JWT, sauf l'authentification et le ping.
 * - Un invité (mode local, rôle GUEST) n'a accès qu'à ce dont il a besoin pour jouer :
 *   voir sa table, la quitter avant le début, et quitter sa partie.
 *   Tout le reste de l'API est réservé aux comptes (rôle PLAYER).
 * - La route qui fait d'un visiteur un invité (POST /api/local/{code}/guest) est publique :
 *   c'est elle qui lui donne son jeton, il ne peut donc pas en avoir avant.
 * - Tout le reste est public : les fichiers de l'interface Vue (qui ne contiennent
 *   aucune donnée), l'ouverture du WebSocket (authentifié ensuite par la trame CONNECT)
 *   et la page d'erreur.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                // API sans état, appelée en JSON : pas de session ni de protection CSRF par formulaire
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Vérifie le jeton JWT de l'en-tête Authorization
                // et en déduit le rôle (PLAYER ou GUEST), voir JwtConfig
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                // Les règles sont lues dans l'ordre : la PREMIÈRE qui correspond s'applique
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/ping").permitAll()
                        // Mode local : s'asseoir comme invité avec le code d'accès
                        .requestMatchers(HttpMethod.POST, "/api/local/*/guest").permitAll()
                        // Ce qu'un invité a le droit de faire (un compte aussi, évidemment)
                        .requestMatchers(HttpMethod.GET, "/api/tables/*").hasAnyRole("PLAYER", "GUEST")
                        .requestMatchers(HttpMethod.POST, "/api/tables/*/leave").hasAnyRole("PLAYER", "GUEST")
                        .requestMatchers(HttpMethod.POST, "/api/tables/*/leave-game").hasAnyRole("PLAYER", "GUEST")
                        // Tout le reste de l'API : comptes uniquement
                        .requestMatchers("/api/**").hasRole("PLAYER")
                        // Interface Vue, WebSocket, page d'erreur
                        .anyRequest().permitAll()
                );
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}