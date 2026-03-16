package server;

import model.GameRecord;
import model.User;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


public class AdminStats {

    private AtomicInteger currentConnected   = new AtomicInteger(0);
    private AtomicInteger totalQuestionsPlayed = new AtomicInteger(0);

    public void playerConnected() {
        currentConnected.incrementAndGet();
    }

    public void playerDisconnected() {
        if (currentConnected.get() > 0) currentConnected.decrementAndGet();
    }

    public void recordQuestionsPlayed(int count) {
        totalQuestionsPlayed.addAndGet(count);
    }

    public int getCurrentConnected() {
        return currentConnected.get();
    }

    public int getTotalQuestionsPlayed() {
        return totalQuestionsPlayed.get();
    }


    public String getMostWins(Map<String, User> users) {
        String bestName = "No data";
        int    maxWins  = 0;

        for (User u : users.values()) {
            if (u.getUsername().equals("admin")) continue;

            int wins = 0;
            for (GameRecord r : u.getScoreHistory()) {
                boolean isCompetitive = r.getGameType().equals("multiplayer") || r.getGameType().equals("public");
                if (r.getScore() > 0 && isCompetitive) wins++;
            }
            if (wins > maxWins) {
                maxWins  = wins;
                bestName = u.getName() + " (" + wins + " wins)";
            }
        }
        return bestName;
    }


    public int getHighestScore(Map<String, User> users) {
        int highest = 0;
        for (User u : users.values()) {
            if (u.getUsername().equals("admin")) continue;
            for (GameRecord r : u.getScoreHistory()) {
                if (r.getScore() > highest) highest = r.getScore();
            }
        }
        return highest;
    }
}
