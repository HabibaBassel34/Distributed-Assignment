package util;

import model.Question;

import java.util.*;
import java.util.stream.Collectors;

public class QuestionBank {
    private List<Question> allQuestions;

    public QuestionBank(List<Question> questions) {
        this.allQuestions = new ArrayList<>(questions);
    }

    public List<String> getCategories() {
        return allQuestions.stream()
                .map(Question::getCategory)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public List<String> getDifficulties() {
        return List.of("easy", "medium", "hard");
    }

    // Q/category
    public List<Question> getQuestions(String category, String difficulty, int count) {
        List<Question> filtered = allQuestions.stream()
                .filter(q -> (category == null || q.getCategory().equalsIgnoreCase(category)))
                .filter(q -> (difficulty == null || q.getDifficulty().equalsIgnoreCase(difficulty)))
                .collect(Collectors.toList());

        Collections.shuffle(filtered);
        return filtered.subList(0, Math.min(count, filtered.size()));
    }

    public int getAvailableCount(String category, String difficulty) {
        return (int) allQuestions.stream()
                .filter(q -> (category == null || q.getCategory().equalsIgnoreCase(category)))
                .filter(q -> (difficulty == null || q.getDifficulty().equalsIgnoreCase(difficulty)))
                .count();
    }

    public List<Question> getAllQuestions() {
        return Collections.unmodifiableList(allQuestions);
    }
}