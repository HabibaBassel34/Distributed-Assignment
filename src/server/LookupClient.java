package server;

import model.Question;

import java.io.*;
import java.net.*;
import java.util.*;


public class LookupClient {

    private String host;
    private int    port;

    public LookupClient(String host, int port) {
        this.host = host;
        this.port = port;
    }


    public List<Question> fetchQuestions(String category, String difficulty, int count) {

        String cat  = (category   == null || category.isBlank())   ? "*" : category;
        String diff = (difficulty == null || difficulty.isBlank()) ? "*" : difficulty;
        String request = "QUERY category=" + cat + " difficulty=" + diff + " count=" + count;

        List<Question> result = new ArrayList<>();

        try (
            Socket       socket = new Socket(host, port);
            PrintWriter  out    = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in   = new BufferedReader(new InputStreamReader(socket.getInputStream()))
        ) {
            socket.setSoTimeout(5000);

            out.println(request);


            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("END")) break;

                if (line.startsWith("ERROR")) {
                    System.err.println("[LookupClient] Server returned error: " + line);
                    break;
                }


                Question q = deserialize(line);
                if (q != null) result.add(q);
            }

        } catch (IOException e) {

            System.err.println("[LookupClient] Could not reach LookupServer: " + e.getMessage());
        }

        return result;
    }


    private Question deserialize(String line) {
        String[] parts = line.split("\\|", 9);
        if (parts.length < 9) return null;

        try {
            int    id         = Integer.parseInt(parts[0]);
            String category   = parts[1];
            String difficulty = parts[2];
            String text       = parts[3];

            List<String> choices = Arrays.asList(parts[4], parts[5], parts[6], parts[7]);
            String answer     = parts[8];
            return new Question(id, text, category, difficulty, choices, answer);
        } catch (Exception e) {
            System.err.println("[LookupClient] Failed to parse question: " + line);
            return null;
        }
    }
}
