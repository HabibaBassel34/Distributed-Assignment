package server;

import model.GameRecord;
import model.User;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AdminStats - Additional Feature 4 (Admin Panel)
 *
 * Keeps track of server-wide statistics that the admin user can view.
 * All fields are updated at runtime; nothing is persisted (stats reset on restart).
 *
 * Stats tracked:
 *   - Current number of connected players
 *   - Total questions played across all sessions since server started
 *
 * Stats that are computed on-demand (from AuthManager data):
 *   - Player with the most wins (game records where score > 0)
 *   - Highest score ever recorded
 *
 * ASSUMPTION: "Win" means a game record with score > 0.
 * ASSUMPTION: Admin user credentials are: username="admin", password="admin123".
 *             This user is stored in users.txt just like regular users, but
 *             ClientHandler checks for this username and shows the admin panel
 *             instead of the normal game menu.
 * ASSUMPTION: Stats are not saved to disk - they reset when the server restarts.
 */
public class AdminStats {

    // AtomicInteger is thread-safe - multiple threads update it concurrently
    private AtomicInteger currentConnected   = new AtomicInteger(0);
    private AtomicInteger totalQuestionsPlayed = new AtomicInteger(0);

    // Called by GameServer.registerClient() when a player logs in
    public void playerConnected() {
        currentConnected.incrementAndGet();
    }

    // Called by GameServer.unregisterClient() when a player disconnects
    public void playerDisconnected() {
        if (currentConnected.get() > 0) currentConnected.decrementAndGet();
    }

    // Called by GameSession.start() after a game ends
    public void recordQuestionsPlayed(int count) {
        totalQuestionsPlayed.addAndGet(count);
    }

    // Simple getter for how many players are online right now
    public int getCurrentConnected() {
        return currentConnected.get();
    }

    // Simple getter for total questions played this session
    public int getTotalQuestionsPlayed() {
        return totalQuestionsPlayed.get();
    }

    /**
     * Finds the player with the most "wins" (games where score > 0).
     * Loops through all users and their game history.
     * Returns "No data" if nobody has played yet.
     *
     * ASSUMPTION: A win is any game where the player scored at least 1 point.
     */
    public String getMostWins(Map<String, User> users) {
        String bestName = "No data";
        int    maxWins  = 0;

        for (User u : users.values()) {
            if (u.getUsername().equals("admin")) continue; // skip admin user

            int wins = 0;
            for (GameRecord r : u.getScoreHistory()) {
                // Only count multiplayer (teams) and public room games as wins
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

    /**
     * Finds the single highest score ever recorded across all users.
     * Returns 0 if nobody has played yet.
     */
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
