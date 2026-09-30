package fr.louis.poker.server.api;

import fr.louis.poker.evaluation.HandEvaluator;
import fr.louis.poker.model.Card;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class PingController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        String hand = HandEvaluator.evaluate(Card.parseAll("As Ks Qs Js Ts")).category().name();
        return Map.of("status", "ok", "test", hand);
    }
}



