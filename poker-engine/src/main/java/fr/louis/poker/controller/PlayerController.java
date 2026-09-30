package fr.louis.poker.controller;

import fr.louis.poker.action.Action;

@FunctionalInterface
public interface PlayerController {

    Action decide(GameView view);
}
