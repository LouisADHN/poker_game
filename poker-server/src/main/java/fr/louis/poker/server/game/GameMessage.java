package fr.louis.poker.server.game;

import java.util.Map;

public record GameMessage(String type, Map<String, Object> data) {
}
