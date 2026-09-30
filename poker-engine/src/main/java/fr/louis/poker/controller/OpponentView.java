package fr.louis.poker.controller;

import fr.louis.poker.model.PlayerStatus;

public record OpponentView(String name, int chips, PlayerStatus status, int streetBet) {
}
