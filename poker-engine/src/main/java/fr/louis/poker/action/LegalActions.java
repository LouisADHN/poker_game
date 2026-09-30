package fr.louis.poker.action;

public record LegalActions(boolean canCheck, int toCall, boolean canRaise, int minRaiseTo, int maxRaiseTo) {
}
