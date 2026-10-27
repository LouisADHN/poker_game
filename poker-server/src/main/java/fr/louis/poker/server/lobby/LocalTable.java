package fr.louis.poker.server.lobby;

/**
 * Une table locale qui vient d'être créée : la table, et son code d'accès.
 *
 * Le code n'est donné qu'ici, à l'hôte qui crée la table (l'écran partagé l'affiche en QR code).
 * Il ne figure jamais dans TableView, qui est diffusé à tout le lobby.
 */
public record LocalTable(TableView table, String joinCode) {
}