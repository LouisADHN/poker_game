package fr.louis.poker.server.lobby;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de concurrence du lobby : de nombreux threads agissent au même instant.
 * Test unitaire pur (ni Spring ni base) : les diffusions WebSocket sont simplement ignorées.
 *
 * @RepeatedTest relance chaque test plusieurs fois : un bug de concurrence
 * ne se manifeste pas forcément à chaque exécution.
 */
class LobbyServiceConcurrencyTest {

    private static final int THREADS = 20;

    private LobbyService lobbyService;

    @BeforeEach
    void setUp() {
        // Canal factice : accepte tous les messages sans rien en faire
        SimpMessagingTemplate messaging = new SimpMessagingTemplate((message, timeout) -> true);
        lobbyService = new LobbyService(messaging);
    }

    /**
     * Lance toutes les tâches au même instant : chaque thread attend le signal de départ,
     * puis tous partent ensemble pour maximiser les chances de collision.
     */
    private void runSimultaneously(List<Runnable> tasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch startSignal = new CountDownLatch(1);

        for (Runnable task : tasks) {
            executor.submit(() -> {
                startSignal.await();
                task.run();
                return null;
            });
        }

        startSignal.countDown(); // Partez !
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Les threads n'ont pas terminé à temps");
    }

    @RepeatedTest(10)
    @DisplayName("20 joueurs se ruent sur les 2 dernières places : exactement 2 réussissent")
    void onlyFreeSeatsCanBeTaken() throws InterruptedException {
        // Table de 3 places : la créatrice + 2 places libres
        TableView table = lobbyService.create(new Seat(0L, "Alice"),
                new CreateTableRequest("Course aux places", 10, 20, 1000, 3));

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger refusals = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();

        for (long userId = 1; userId <= THREADS; userId++) {
            Seat seat = new Seat(userId, "Joueur" + userId);
            tasks.add(() -> {
                try {
                    lobbyService.join(table.id(), seat);
                    successes.incrementAndGet();
                } catch (LobbyConflictException e) {
                    refusals.incrementAndGet();
                }
            });
        }

        runSimultaneously(tasks);

        assertEquals(2, successes.get());
        assertEquals(THREADS - 2, refusals.get());
        assertEquals(3, lobbyService.get(table.id()).players().size());
    }

    @RepeatedTest(10)
    @DisplayName("Un joueur tente de rejoindre 20 tables en même temps : il n'est assis qu'à une seule")
    void playerCanOnlySitAtOneTable() throws InterruptedException {
        List<Long> tableIds = new ArrayList<>();
        for (long ownerId = 1; ownerId <= THREADS; ownerId++) {
            TableView table = lobbyService.create(new Seat(ownerId, "Hôte" + ownerId),
                    new CreateTableRequest("Table " + ownerId, 10, 20, 1000, 4));
            tableIds.add(table.id());
        }

        Seat louis = new Seat(999L, "Louis");
        AtomicInteger successes = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();

        for (long tableId : tableIds) {
            tasks.add(() -> {
                try {
                    lobbyService.join(tableId, louis);
                    successes.incrementAndGet();
                } catch (LobbyConflictException e) {
                    // Attendu pour toutes les tables sauf une
                }
            });
        }

        runSimultaneously(tasks);

        assertEquals(1, successes.get());
        long tablesWithLouis = lobbyService.list().stream()
                .filter(t -> t.players().stream().anyMatch(seat -> seat.userId().equals(999L)))
                .count();
        assertEquals(1, tablesWithLouis);
    }
}