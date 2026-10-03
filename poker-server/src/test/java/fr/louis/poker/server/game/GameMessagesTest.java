package fr.louis.poker.server.game;

import fr.louis.poker.action.Action;
import fr.louis.poker.action.LegalActions;
import fr.louis.poker.controller.GameView;
import fr.louis.poker.controller.OpponentView;
import fr.louis.poker.engine.Street;
import fr.louis.poker.evaluation.HandEvaluator;
import fr.louis.poker.event.GameEvent;
import fr.louis.poker.model.Card;
import fr.louis.poker.model.PlayerStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameMessagesTest {

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Événements du moteur vers messages")
    class Events {

        @Test
        @DisplayName("HandStarted devient HAND_STARTED avec numéro et dealer")
        void handStarted() {
            GameMessage message = GameMessages.fromEvent(new GameEvent.HandStarted(3, "Louis"));
            assertEquals("HAND_STARTED", message.type());
            assertEquals(Map.of("handNumber", 3, "dealer", "Louis"), message.data());
        }

        @Test
        @DisplayName("Les cartes privées sont en notation courte")
        void holeCards() {
            GameMessage message = GameMessages.fromEvent(
                    new GameEvent.HoleCardsDealt("Louis", Card.parseAll("As Th")));
            assertEquals("HOLE_CARDS", message.type());
            assertEquals(List.of("As", "Th"), message.data().get("cards"));
        }

        @Test
        @DisplayName("Le board indique le tour et toutes les cartes révélées")
        void board() {
            GameMessage message = GameMessages.fromEvent(
                    new GameEvent.BoardDealt(Street.FLOP, Card.parseAll("Ah 7c 2d")));
            assertEquals("BOARD", message.type());
            assertEquals("FLOP", message.data().get("street"));
            assertEquals(List.of("Ah", "7c", "2d"), message.data().get("board"));
        }

        @Test
        @DisplayName("Une relance garde son type et son total")
        void playerRaised() {
            GameMessage message = GameMessages.fromEvent(
                    new GameEvent.PlayerActed("Bob", new Action.Raise(120), 120, 880));
            assertEquals("PLAYER_ACTED", message.type());
            assertEquals(Map.of("type", "RAISE", "total", 120), message.data().get("action"));
            assertEquals(880, message.data().get("chipsLeft"));
        }

        @Test
        @DisplayName("Un fold reste identifiable, contrairement à un record vide converti tel quel")
        void playerFolded() {
            GameMessage message = GameMessages.fromEvent(
                    new GameEvent.PlayerActed("Bob", new Action.Fold(), 0, 1000));
            assertEquals(Map.of("type", "FOLD"), message.data().get("action"));
        }

        @Test
        @DisplayName("Au showdown, la catégorie de la main est donnée en texte")
        void handRevealed() {
            var value = HandEvaluator.evaluate(Card.parseAll("As Ad Ah Kc Kd"));
            GameMessage message = GameMessages.fromEvent(
                    new GameEvent.HandRevealed("Louis", Card.parseAll("As Ad"), value));
            assertEquals("HAND_REVEALED", message.type());
            assertEquals("FULL_HOUSE", message.data().get("category"));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Message YOUR_TURN")
    class YourTurn {

        @Test
        @DisplayName("Contient la situation complète et les actions légales")
        void containsFullSituation() {
            LegalActions legal = new LegalActions(false, 20, true, 40, 980);
            OpponentView bob = new OpponentView("Bob", 990, PlayerStatus.ACTIVE, 10);
            GameView view = new GameView("Louis", Card.parseAll("As Kd"), 980, Street.PREFLOP,
                    List.of(), 30, 20, 0, List.of(bob), legal);

            GameMessage message = GameMessages.yourTurn(view);

            assertEquals("YOUR_TURN", message.type());
            assertEquals("PREFLOP", message.data().get("street"));
            assertEquals(List.of("As", "Kd"), message.data().get("holeCards"));
            assertEquals(30, message.data().get("pot"));
            assertEquals(legal, message.data().get("legalActions"));
            assertEquals(List.of(bob), message.data().get("opponents"));
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("Actions reçues du navigateur")
    class IncomingActions {

        @Test
        @DisplayName("Chaque type est converti en l'action correspondante")
        void eachTypeIsConverted() {
            assertEquals(new Action.Fold(), new ActionMessage("FOLD", null).toAction());
            assertEquals(new Action.Check(), new ActionMessage("CHECK", null).toAction());
            assertEquals(new Action.Call(), new ActionMessage("CALL", null).toAction());
            assertEquals(new Action.Bet(50), new ActionMessage("BET", 50).toAction());
            assertEquals(new Action.Raise(120), new ActionMessage("RAISE", 120).toAction());
            assertEquals(new Action.AllIn(), new ActionMessage("ALL_IN", null).toAction());
        }

        @Test
        @DisplayName("Une relance sans montant est refusée")
        void raiseWithoutTotalIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new ActionMessage("RAISE", null).toAction());
        }

        @Test
        @DisplayName("Un type inconnu ou absent est refusé")
        void unknownTypeIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> new ActionMessage("BLUFF", null).toAction());
            assertThrows(IllegalArgumentException.class, () -> new ActionMessage(null, null).toAction());
        }

        @Test
        @DisplayName("Aller-retour : action -> message -> action redonne l'action d'origine")
        void roundTrip() {
            List<Action> actions = List.of(new Action.Fold(), new Action.Check(), new Action.Call(),
                    new Action.Bet(50), new Action.Raise(120), new Action.AllIn());

            for (Action original : actions) {
                Map<String, Object> sent = GameMessages.action(original);
                ActionMessage received = new ActionMessage((String) sent.get("type"), (Integer) sent.get("total"));
                assertEquals(original, received.toAction(), "Échec de l'aller-retour pour " + original);
            }
        }

        @Test
        @DisplayName("BlindPosted devient BLIND_POSTED avec le joueur et le montant")
        void blindPosted() {
            GameMessage message = GameMessages.fromEvent(new GameEvent.BlindPosted("Louis", 10));
            assertEquals("BLIND_POSTED", message.type());
            assertEquals(Map.of("player", "Louis", "amount", 10), message.data());
        }
    }
}