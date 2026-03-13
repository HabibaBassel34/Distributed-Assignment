package model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class GameRecord {
    private String gameType;
    private int score;
    private int totalQuestions;
    private int correctAnswers;
    private long timestamp; // millis

    public GameRecord(String gameType, int score, int totalQuestions, int correctAnswers) {
        this.gameType = gameType;
        this.score = score;
        this.totalQuestions = totalQuestions;
        this.correctAnswers = correctAnswers;
        this.timestamp = System.currentTimeMillis();
    }

    // AuthManager load from CSV
    public GameRecord(String gameType, int score, long timestamp) {
        this.gameType = gameType;
        this.score = score;
        this.timestamp = timestamp;
    }

    public String getGameType() {
        return gameType;
    }

    public int getScore() {
        return score;
    }

    public int getTotalQuestions() {
        return totalQuestions;
    }

    public int getCorrectAnswers() {
        return correctAnswers;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        LocalDateTime dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String base = String.format("[%s] Type: %-12s Score: %3d", dt.format(fmt), gameType, score);
        if (totalQuestions > 0)
            base += String.format(" | Correct: %d/%d", correctAnswers, totalQuestions);
        return base;
    }

    public String toCSVRow(String username) {
        return username + "," + gameType + "," + score + "," + timestamp;
    }
}