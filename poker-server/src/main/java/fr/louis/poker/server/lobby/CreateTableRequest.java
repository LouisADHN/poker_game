package fr.louis.poker.server.lobby;

import jakarta.validation.constraints.*;

public record CreateTableRequest(@NotBlank(message = "Le nom ne peut pas être vide")
                                 @Size(max = 30, message = "Le nom peut contenir maximum 30 caractères")
                                 String name,
                                 @Positive(message = "La smallBlind doit être positive")
                                 int smallBlind,
                                 @Positive(message = "La bigBlind doit être positive")
                                 int bigBlind,
                                 @Positive(message = "Le startingChips doit être positif")
                                 int startingChips,
                                 @Min(value = 2, message = "Le nombre minimum de joueurs est 2")
                                 @Max(value = 10, message = "Le nombre maximum de joueurs est 10")
                                 int maxPlayers) {

    public TableSettings toSettings() {
        return new TableSettings(smallBlind, bigBlind, startingChips, maxPlayers);
    }
}
