package fr.louis.poker.action;

public sealed interface Action {

    record Fold() implements Action {}

    record Check() implements Action {}

    record Call() implements Action {}

    /**
     * @param total Total misé par le joueur sur ce tour après l'action ("relancer à").
     */
    record Bet(int total) implements Action {
        public Bet {
            if (total <= 0) {
                throw new IllegalArgumentException("Le total doit être positif : "+total);
            }
        }
    }

    /**
     * @param total Total misé par le joueur sur ce tour après l'action ("relancer à").
     */
    record Raise(int total) implements Action {
        public Raise {
            if (total <= 0) {
                throw new IllegalArgumentException("Le total doit être positif : "+total);
            }
        }
    }

    record AllIn() implements Action {}
}
