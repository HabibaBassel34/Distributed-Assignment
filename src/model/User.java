package model;

import java.util.ArrayList;
import java.util.List;

public class User {
    private String name;
    private String username;
    private String password;
    private List<GameRecord> scoreHistory;

    public User(String name, String username, String password) {
        this.name = name;
        this.username = username;
        this.password = password;
        this.scoreHistory = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public List<GameRecord> getScoreHistory() {
        return scoreHistory;
    }

    public void addGameRecord(GameRecord record) {
        scoreHistory.add(record);
    }

    public boolean checkPassword(String pw) {
        return this.password.equals(pw);
    }
}