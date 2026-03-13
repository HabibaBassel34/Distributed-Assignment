package util;

import model.Question;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class FileLoader {

    // CSV
    public static List<String[]> loadCSV(String filePath) throws IOException {
        List<String[]> rows = new ArrayList<>();
        File f = new File(filePath);
        if (!f.exists())
            return rows;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                rows.add(line.split(",", -1));
            }
        }
        return rows;
    }

    public static void saveCSV(String filePath, List<String[]> rows) throws IOException {
        ensureParentDirs(filePath);
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath))) {
            for (String[] row : rows) {
                pw.println(String.join(",", row));
            }
        }
    }

    public static void appendCSV(String filePath, String[] row) throws IOException {
        ensureParentDirs(filePath);
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, true))) {
            pw.println(String.join(",", row));
        }
    }

    public static Map<String, String> loadConfig(String filePath) throws IOException {
        Map<String, String> config = new HashMap<>();

        config.put("min_players_per_team", "1");
        config.put("max_players_per_team", "4");
        config.put("question_timeout_seconds", "15");
        config.put("server_port", "5555");

        File f = new File(filePath);
        if (!f.exists()) {
            System.out.println("[INFO] Config file not found, using defaults.");
            return config;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                String[] parts = line.split("=", 2);
                if (parts.length == 2)
                    config.put(parts[0].trim(), parts[1].trim());
            }
        }
        System.out.println("[INFO] Config loaded: " + config);
        return config;
    }

    // Questions bank

    public static List<Question> loadQuestions(String filePath) throws IOException {
        List<Question> questions = new ArrayList<>();
        File f = new File(filePath);
        if (!f.exists()) {
            System.out.println("[WARN] Questions file not found: " + filePath);
            return questions;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            int id = 0;
            String category = "", difficulty = "", text = "", answer = "";
            List<String> choices = new ArrayList<>();

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#"))
                    continue;

                if (line.isEmpty()) {
                    if (!text.isEmpty() && !choices.isEmpty() && !answer.isEmpty()) {
                        questions.add(new Question(id, text, category, difficulty, new ArrayList<>(choices), answer));
                    }
                    id = 0;
                    category = "";
                    difficulty = "";
                    text = "";
                    answer = "";
                    choices.clear();
                    continue;
                }

                if (line.startsWith("ID:"))
                    id = Integer.parseInt(line.substring(3).trim());
                else if (line.startsWith("Category:"))
                    category = line.substring(9).trim();
                else if (line.startsWith("Difficulty:"))
                    difficulty = line.substring(11).trim().toLowerCase();
                else if (line.startsWith("Question:"))
                    text = line.substring(9).trim();
                else if (line.startsWith("A:"))
                    choices.add(line.substring(2).trim());
                else if (line.startsWith("B:"))
                    choices.add(line.substring(2).trim());
                else if (line.startsWith("C:"))
                    choices.add(line.substring(2).trim());
                else if (line.startsWith("D:"))
                    choices.add(line.substring(2).trim());
                else if (line.startsWith("Answer:"))
                    answer = line.substring(7).trim().toUpperCase();
            }
            if (!text.isEmpty() && !choices.isEmpty() && !answer.isEmpty()) {
                questions.add(new Question(id, text, category, difficulty, new ArrayList<>(choices), answer));
            }
        }

        System.out.println("[INFO] Loaded " + questions.size() + " questions.");
        return questions;
    }

    // Utility

    private static void ensureParentDirs(String filePath) {
        File parent = new File(filePath).getParentFile();
        if (parent != null && !parent.exists())
            parent.mkdirs();
    }
}