package server;

import model.GameRecord;
import model.Question;
import model.User;
import util.QuestionBank;

import java.util.*;
import java.util.concurrent.*;

public class GameSession {
    private List<ClientHandler> handlers;
    private List<User> players;
    private List<Question> questions;
    private Map<String, Integer> scores; // username -> score
    private Map<String, List<String>> results; // username -> result lines
    private int questionTimeoutSeconds;
    private String gameType;
    private AuthManager authManager;

    private static final int SCORE_EASY = 10;
    private static final int SCORE_MEDIUM = 20;
    private static final int SCORE_HARD = 30;

    public GameSession(List<ClientHandler> handlers, List<User> players,
            List<Question> questions, int questionTimeoutSeconds,
            String gameType, AuthManager authManager) {
        this.handlers = new ArrayList<>(handlers);
        this.players = new ArrayList<>(players);
        this.questions = questions;
        this.questionTimeoutSeconds = questionTimeoutSeconds;
        this.gameType = gameType;
        this.authManager = authManager;
        this.scores = new LinkedHashMap<>();
        this.results = new LinkedHashMap<>();
        for (User u : players) {
            scores.put(u.getUsername(), 0);
            results.put(u.getUsername(), new ArrayList<>());
        }
    }

    public void start() {
        broadcast("==============================================");
        broadcast("           GAME STARTING NOW!               ");
        broadcast("  " + questions.size() + " questions | " + questionTimeoutSeconds + "s per question");
        broadcast("==============================================\n");

        for (int i = 0; i < questions.size(); i++) {
            runQuestion(i + 1, questions.get(i));
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
        }

        showFinalResults();
        saveScores();
    }

    private void runQuestion(int qNum, Question q) {
        broadcast("\n--- Question " + qNum + " of " + questions.size() + " ---");
        broadcast(q.toString());
        broadcast("You have " + questionTimeoutSeconds + " seconds. Enter A, B, C, or D:");

        Map<String, String> answersMap = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newCachedThreadPool();

        for (int i = 0; i < handlers.size(); i++) {
            final ClientHandler handler = handlers.get(i);
            final User player = players.get(i);
            executor.submit(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        String answer = handler.readLine();
                        if (answer == null)
                            break;
                        answer = answer.trim().toUpperCase();
                        if (!answer.matches("[ABCD]")) {
                            handler.send("Invalid input. Enter A, B, C, or D.");
                            continue;
                        }
                        answersMap.putIfAbsent(player.getUsername(), answer);
                        handler.send("Answer received: " + answer);
                        if (gameType.equals("single"))
                            latch.countDown();
                        break;
                    }
                } catch (Exception ignored) {
                }
            });
        }

        scheduleTimerBroadcasts(latch);

        try {
            latch.await(questionTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }

        executor.shutdownNow();
        evaluateAnswers(q, answersMap);
    }

    private void scheduleTimerBroadcasts(CountDownLatch latch) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        int[] alertsAt = { questionTimeoutSeconds / 2, 10, 5, 3, 1 };
        for (int t : alertsAt) {
            if (t > 0 && t < questionTimeoutSeconds) {
                int delay = questionTimeoutSeconds - t;
                scheduler.schedule(() -> {
                    if (latch.getCount() > 0)
                        broadcast("⏱  " + t + " seconds remaining!");
                }, delay, TimeUnit.SECONDS);
            }
        }
        scheduler.schedule(() -> {
            broadcast("⏱  Time's up!");
            latch.countDown();
            scheduler.shutdown();
        }, questionTimeoutSeconds, TimeUnit.SECONDS);
    }

    private void evaluateAnswers(Question q, Map<String, String> answersMap) {
        broadcast("\n--- Results ---");
        broadcast("Correct answer: " + q.getCorrectAnswer());

        for (int i = 0; i < players.size(); i++) {
            User player = players.get(i);
            ClientHandler handler = handlers.get(i);
            String username = player.getUsername();
            String given = answersMap.get(username);

            String resultLine;
            if (given == null) {
                resultLine = player.getName() + " - No answer (0 pts)";
                results.get(username)
                        .add("Q: " + q.getText() + " | Your answer: (none) | Correct: " + q.getCorrectAnswer() + " ❌");
            } else if (q.isCorrect(given)) {
                int pts = pointsFor(q.getDifficulty());
                scores.merge(username, pts, Integer::sum);
                resultLine = player.getName() + " - CORRECT! (+" + pts + " pts)";
                results.get(username).add("Q: " + q.getText() + " | Your answer: " + given + " ✅ (+" + pts + " pts)");
            } else {
                resultLine = player.getName() + " - Wrong: " + given + " (0 pts)";
                results.get(username)
                        .add("Q: " + q.getText() + " | Your answer: " + given + " ❌ Correct: " + q.getCorrectAnswer());
            }
            broadcast("  " + resultLine);
        }

        broadcast("\n  📊 Current Scores:");
        for (int i = 0; i < players.size(); i++) {
            String u = players.get(i).getUsername();
            broadcast("  " + players.get(i).getName() + ": " + scores.get(u) + " pts");
        }
    }

    private int pointsFor(String difficulty) {
        switch (difficulty.toLowerCase()) {
            case "hard":
                return SCORE_HARD;
            case "medium":
                return SCORE_MEDIUM;
            default:
                return SCORE_EASY;
        }
    }

    private void showFinalResults() {
        broadcast("\n==============================================");
        broadcast("              GAME OVER!                    ");
        broadcast("==============================================");

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());

        int rank = 1;
        for (Map.Entry<String, Integer> entry : sorted) {
            User user = players.stream().filter(p -> p.getUsername().equals(entry.getKey())).findFirst().orElse(null);
            String name = user != null ? user.getName() : entry.getKey();
            broadcast("  #" + rank++ + " " + name + " - " + entry.getValue() + " pts");
        }

        broadcast("\n--- Your Question Breakdown ---");
        for (int i = 0; i < players.size(); i++) {
            User player = players.get(i);
            handlers.get(i).send("\n" + player.getName() + "'s breakdown:");
            for (String r : results.get(player.getUsername())) {
                handlers.get(i).send("  " + r);
            }
        }
    }

    private void saveScores() {
        int total = questions.size();
        for (int i = 0; i < players.size(); i++) {
            User player = players.get(i);
            int score = scores.getOrDefault(player.getUsername(), 0);
            long correct = results.get(player.getUsername()).stream()
                    .filter(r -> r.contains("✅")).count();
            GameRecord record = new GameRecord(gameType, score, total, (int) correct);
            try {
                authManager.saveScore(player, record);
            } catch (Exception e) {
                System.err.println("[WARN] Could not save score for " + player.getUsername() + ": " + e.getMessage());
            }
        }
    }

    private void broadcast(String message) {
        for (ClientHandler h : handlers)
            h.send(message);
    }
}