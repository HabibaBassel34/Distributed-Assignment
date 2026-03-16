package server;

import model.Question;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * LookupClient - used by GameServer to ask LookupServer for questions.
 * Sends a QUERY request, reads back serialized questions until "END".
 *
 * ASSUMPTION: LookupServer must already be running before GameServer starts
 * a game that needs questions. If the connection fails, an empty list is
 * returned and the GameServer falls back to its local QuestionBank.
 *
 * ASSUMPTION: Socket timeout is 5 seconds - if LookupServer doesn't respond
 * in time we give up and fall back to local questions.
 */
public class LookupClient {

    private String host; // where the LookupServer is running
    private int    port; // LookupServer port (default 5556)

    public LookupClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Asks the LookupServer for questions matching the given criteria.
     * Pass null for category or difficulty to match everything (random trivia).
     *
     * @param category   e.g. "Geography", or null for any
     * @param difficulty e.g. "easy", or null for any
     * @param count      how many questions to request
     * @return list of questions, or empty list if connection failed
     */
    public List<Question> fetchQuestions(String category, String difficulty, int count) {
        // Build the query string - use * as wildcard for null values
        String cat  = (category   == null || category.isBlank())   ? "*" : category;
        String diff = (difficulty == null || difficulty.isBlank()) ? "*" : difficulty;
        String request = "QUERY category=" + cat + " difficulty=" + diff + " count=" + count;

        List<Question> result = new ArrayList<>();

        try (
            Socket       socket = new Socket(host, port);
            PrintWriter  out    = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader in   = new BufferedReader(new InputStreamReader(socket.getInputStream()))
        ) {
            socket.setSoTimeout(5000); // give up after 5 seconds if no response

            out.println(request); // send the query

            // Read responses line by line until we see "END"
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals("END")) break; // server finished sending

                if (line.startsWith("ERROR")) {
                    System.err.println("[LookupClient] Server returned error: " + line);
                    break;
                }

                // Try to deserialize this line into a Question object
                Question q = deserialize(line);
                if (q != null) result.add(q);
            }

        } catch (IOException e) {
            // Connection failed - caller will fall back to local QuestionBank
            System.err.println("[LookupClient] Could not reach LookupServer: " + e.getMessage());
        }

        return result;
    }

    /**
     * Converts a pipe-separated string back into a Question object.
     * Format: id|category|difficulty|text|A|B|C|D|answer
     *
     * ASSUMPTION: The string must have exactly 9 pipe-separated fields.
     * If parsing fails for any reason, returns null (caller skips it).
     */
    private Question deserialize(String line) {
        String[] parts = line.split("\\|", 9);
        if (parts.length < 9) return null; // malformed line

        try {
            int    id         = Integer.parseInt(parts[0]);
            String category   = parts[1];
            String difficulty = parts[2];
            String text       = parts[3];
            // Choices A, B, C, D are at indices 4-7
            List<String> choices = Arrays.asList(parts[4], parts[5], parts[6], parts[7]);
            String answer     = parts[8];
            return new Question(id, text, category, difficulty, choices, answer);
        } catch (Exception e) {
            System.err.println("[LookupClient] Failed to parse question: " + line);
            return null;
        }
    }
}
