package server;

import model.User;
import model.GameRecord;
import util.FileLoader;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;

public class AuthManager {
    private Map<String, User> users; // username -> User
    private String usersFilePath;
    private String scoresFilePath;

    public AuthManager(String usersFilePath, String scoresFilePath) {
        this.usersFilePath = usersFilePath;
        this.scoresFilePath = scoresFilePath;
        this.users = new ConcurrentHashMap<>();
        loadUsers();
        loadScores();
    }

    private void loadUsers() {
        try {
            List<String[]> data = FileLoader.loadCSV(usersFilePath);
            for (String[] row : data) {
                if (row.length >= 3) {
                    String name = row[0].trim();
                    String username = row[1].trim();
                    String password = row[2].trim();
                    users.put(username, new User(name, username, password));
                }
            }
            System.out.println("[AUTH] Loaded " + users.size() + " users");
        } catch (IOException e) {
            System.err.println("[ERROR] Could not load users: " + e.getMessage());
        }
    }

    private void loadScores() {
        try {
            List<String[]> data = FileLoader.loadCSV(scoresFilePath);
            for (String[] row : data) {
                if (row.length >= 4) {
                    String username = row[0].trim();
                    String gameType = row[1].trim();
                    int score;
                    long timestamp;
                    try {
                        score = Integer.parseInt(row[2].trim());
                        timestamp = Long.parseLong(row[3].trim());
                    } catch (NumberFormatException e) {
                        continue;
                    }

                    User user = users.get(username);
                    if (user != null) {
                        user.addGameRecord(new GameRecord(gameType, score, timestamp));
                    }
                }
            }
            System.out.println("[AUTH] Loaded score history");
        } catch (IOException e) {
            System.err.println("[WARN] Could not load scores: " + e.getMessage());
        }
    }

    public User getUser(String username) {
        return users.get(username);
    }

    public Map<String, User> getAllUsers() {
        return Collections.unmodifiableMap(users);
    }

    public LoginResult login(String username, String password) {
        User user = users.get(username);
        if (user == null) {
            return new LoginResult(false, "ERROR 404: Username not found", null);
        }
        if (!user.getPassword().equals(password)) {
            return new LoginResult(false, "ERROR 401: Incorrect password", null);
        }
        return new LoginResult(true, "Login successful!", user);
    }

    public LoginResult register(String name, String username, String password) {
        if (name == null || name.trim().isEmpty())
            return new LoginResult(false, "ERROR 400: Name cannot be empty", null);
        if (username == null || username.trim().isEmpty())
            return new LoginResult(false, "ERROR 400: Username cannot be empty", null);
        if (password == null || password.trim().isEmpty())
            return new LoginResult(false, "ERROR 400: Password cannot be empty", null);

        if (users.containsKey(username)) {
            return new LoginResult(false, "ERROR 409: Username already exists", null);
        }

        User newUser = new User(name.trim(), username.trim(), password.trim());
        users.put(username.trim(), newUser);

        try {
            saveUsers();
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to save user: " + e.getMessage());
            users.remove(username);
            return new LoginResult(false, "ERROR: Could not save user data", null);
        }

        return new LoginResult(true, "Registration successful! Welcome, " + name + "!", newUser);
    }

    private void saveUsers() throws IOException {
        List<String[]> data = new ArrayList<>();
        for (User user : users.values()) {
            data.add(new String[] { user.getName(), user.getUsername(), user.getPassword() });
        }
        FileLoader.saveCSV(usersFilePath, data);
    }

    public void saveScore(User user, GameRecord record) throws IOException {
        user.addGameRecord(record);
        String[] row = { user.getUsername(), record.getGameType(),
                String.valueOf(record.getScore()), String.valueOf(record.getTimestamp()) };
        FileLoader.appendCSV(scoresFilePath, row);
    }

    public static class LoginResult {
        private final boolean success;
        public final String message;
        public final User user;

        public LoginResult(boolean success, String message, User user) {
            this.success = success;
            this.message = message;
            this.user = user;
        }

        public boolean isSuccess() {
            return success;
        }
    }
}