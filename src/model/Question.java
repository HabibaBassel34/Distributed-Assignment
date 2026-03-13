package model;

import java.util.List;

public class Question {
    private int id;
    private String text;
    private String category;
    private String difficulty;
    private List<String> choices;
    private String correctAnswer;

    public Question(int id, String text, String category, String difficulty, List<String> choices,
            String correctAnswer) {
        this.id = id;
        this.text = text;
        this.category = category;
        this.difficulty = difficulty;
        this.choices = choices;
        this.correctAnswer = correctAnswer.toUpperCase();
    }

    public int getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public String getCategory() {
        return category;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public List<String> getChoices() {
        return choices;
    }

    public String getCorrectAnswer() {
        return correctAnswer;
    }

    public boolean isCorrect(String answer) {
        return answer != null && answer.trim().equalsIgnoreCase(correctAnswer);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Q: ").append(text).append("\n");
        String[] labels = { "A", "B", "C", "D" };
        for (int i = 0; i < choices.size(); i++) {
            sb.append("  ").append(labels[i]).append(") ").append(choices.get(i)).append("\n");
        }
        return sb.toString();
    }
}