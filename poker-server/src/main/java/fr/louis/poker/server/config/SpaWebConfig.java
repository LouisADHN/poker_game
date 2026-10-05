package fr.louis.poker.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Sert l'application Vue compilée, placée dans les ressources sous /static.
 *
 * Vue Router gère les pages dans le navigateur (/tables/3, /leaderboard…).
 * Quand on recharge une de ces pages, le navigateur demande cette adresse au serveur :
 * on renvoie alors index.html, et Vue Router affiche la bonne page.
 *
 * Les contrôleurs (/api/...) sont toujours prioritaires : ce gestionnaire
 * ne traite que les requêtes qu'aucun contrôleur n'a prises en charge.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

    private static final String INDEX = "index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaResourceResolver());
    }

    private static class SpaResourceResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            // 1. Un vrai fichier (JavaScript, CSS, image…) : on le sert tel quel
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }

            // 2. Une route de l'API inconnue, ou un fichier manquant (avec une extension) :
            //    vraie erreur 404, pour ne pas renvoyer une page HTML à la place
            if (resourcePath.startsWith("api/") || resourcePath.startsWith("ws") || resourcePath.contains(".")) {
                return null;
            }

            // 3. Sinon, c'est une page de l'application : Vue Router s'en occupe
            Resource index = location.createRelative(INDEX);
            return index.exists() ? index : null;
        }
    }
}