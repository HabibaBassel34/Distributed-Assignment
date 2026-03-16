package server;

import model.Question;
import util.FileLoader;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;


public class LookupServer {

    private static final int DEFAULT_PORT = 5556;
    private static final String QUESTIONS_FILE = "data/questions.txt";

    private int port;
    private List<Question> questions; // all questions loaded from file

    // CachedThreadPool handles multiple simultaneous requests (requirement)
    private ExecutorService executor = Executors.newCachedThreadPool();

    public LookupServer(int port) throws IOException {
        this.port = port;
        this.questions = FileLoader.loadQuestions(QUESTIONS_FILE);
        System.out.println("[LookupServer] Loaded " + questions.size() + " questions on port " + port);
    }


    public void start() {
        System.out.println("[LookupServer] Listening on port " + port + "...");
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket client = serverSocket.accept();
                System.out.println("[LookupServer] Got request from " + client.getInetAddress());
                executor.submit(() -> handleRequest(client));
            }
        } catch (IOException e) {
            System.err.println("[LookupServer] Error: " + e.getMessage());
        }
    }


    private void handleRequest(Socket socket) {
        try (
            BufferedReader in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter    out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            String request = in.readLine();

            System.out.println("[LookupServer] Request from " + socket.getInetAddress()
                    + " -> " + request);

            // Basic error check - reject empty requests
            if (request == null || request.trim().isEmpty()) {
                System.out.println("[LookupServer] Empty request - sending ERROR");
                out.println("ERROR: empty request");
                out.println("END");
                return;
            }

            List<Question> result = processQuery(request.trim());

            System.out.println("[LookupServer] Sending " + result.size() + " question(s):");
            for (Question q : result) {
                String preview = q.getText().length() > 45
                        ? q.getText().substring(0, 45) + "..." : q.getText();
                System.out.println("[LookupServer]   [" + q.getCategory()
                        + "/" + q.getDifficulty() + "] " + preview);
                out.println(serialize(q));
            }
            out.println("END");
            System.out.println("[LookupServer] Done. Sent END to client.");

        } catch (IOException e) {
            System.err.println("[LookupServer] Request error: " + e.getMessage());
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }


    private List<Question> processQuery(String request) {
        if (!request.startsWith("QUERY")) {
            return new ArrayList<>();
        }

        String category   = null;
        String difficulty = null;
        int    count      = 10;


        String[] tokens = request.split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String[] kv = tokens[i].split("=", 2);
            if (kv.length != 2) continue;

            if (kv[0].equals("category")  && !kv[1].equals("*")) category   = kv[1];
            if (kv[0].equals("difficulty") && !kv[1].equals("*")) difficulty = kv[1];
            if (kv[0].equals("count")) {
                try { count = Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {}
            }
        }


        System.out.println("[LookupServer] Parsed -> category=" + (category == null ? "*" : category)
                + " | difficulty=" + (difficulty == null ? "*" : difficulty)
                + " | count=" + count);


        List<Question> filtered = new ArrayList<>();
        for (Question q : questions) {
            boolean catMatch  = (category   == null || q.getCategory().equalsIgnoreCase(category));
            boolean diffMatch = (difficulty == null || q.getDifficulty().equalsIgnoreCase(difficulty));
            if (catMatch && diffMatch) filtered.add(q);
        }

        System.out.println("[LookupServer] Matched " + filtered.size()
                + " question(s), will return " + Math.min(count, filtered.size()));
        Collections.shuffle(filtered);
        return filtered.subList(0, Math.min(count, filtered.size()));
    }


    private String serialize(Question q) {
        List<String> ch = q.getChoices();
        String c0 = ch.size() > 0 ? ch.get(0) : "";
        String c1 = ch.size() > 1 ? ch.get(1) : "";
        String c2 = ch.size() > 2 ? ch.get(2) : "";
        String c3 = ch.size() > 3 ? ch.get(3) : "";
        return q.getId() + "|" + q.getCategory() + "|" + q.getDifficulty() + "|"
             + q.getText() + "|" + c0 + "|" + c1 + "|" + c2 + "|" + c3 + "|"
             + q.getCorrectAnswer();
    }


    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); }
            catch (NumberFormatException e) { System.err.println("Bad port, using " + DEFAULT_PORT); }
        }
        try {
            new LookupServer(port).start();
        } catch (IOException e) {
            System.err.println("[LookupServer] Could not start: " + e.getMessage());
        }
    }
}
