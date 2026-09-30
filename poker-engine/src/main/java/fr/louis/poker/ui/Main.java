package fr.louis.poker.ui;

import fr.louis.poker.controller.PlayerController;
import fr.louis.poker.engine.GameEngine;
import fr.louis.poker.model.Player;
import fr.louis.poker.model.Table;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;

public class Main {

    private static final int STARTING_CHIPS = 1000;
    private static final int BOT_COUNT = 3;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Ton pseudo : ");
        String name = scanner.nextLine().trim();
        if (name.isBlank()) {
            name = "Joueur";
        }

        Table table = new Table(10, 20);
        Map<Player, PlayerController> controllers = new HashMap<>();

        Player human = new Player(name, STARTING_CHIPS);
        table.addPlayer(human);
        controllers.put(human, new ConsoleController(scanner));

        for (int i = 1; i <= BOT_COUNT; i++) {
            Player bot = new Player("Bot" + i, STARTING_CHIPS);
            table.addPlayer(bot);
            controllers.put(bot, new SimpleBot());
        }

        GameEngine engine = new GameEngine(table, controllers, new Random());
        engine.addListener(new ConsoleUI(name));

        while (!engine.isGameOver() && human.getChips() > 0) {
            engine.playHand();

            System.out.println();
            System.out.println("Tapis : " + table.getPlayers());
            System.out.print("Entrée pour la main suivante, q pour quitter : ");
            if (scanner.nextLine().trim().equalsIgnoreCase("q")) {
                break;
            }
        }

        if (human.getChips() == 0) {
            System.out.println("Tu n'as plus de jetons. Fin de la partie !");
        } else if (engine.isGameOver()) {
            System.out.println("Bravo, tu as remporté tous les jetons !");
        } else {
            System.out.println("Partie arrêtée avec " + human.getChips() + " jetons.");
        }
    }
}