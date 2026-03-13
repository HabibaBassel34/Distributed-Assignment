package server;

import model.User;
import util.QuestionBank;

import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {
    private Socket socket;
    private AuthManager authManager;
    private QuestionBank questionBank;
    private GameServer gameServer;

    private BufferedReader in;
    private PrintWriter out;

    private User currentUser;
    private volatile boolean running = true;

    public ClientHandler(Socket socket, AuthManager authManager,
            QuestionBank questionBank, GameServer gameServer) {
        this.socket = socket;
        this.authManager = authManager;
        this.questionBank = questionBank;
        this.gameServer = gameServer;
    }

    @Override
    public void run() {
        try {
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            send("==============================================");
            send("   Welcome to the Multiplayer Trivia Game!  ");
            send("==============================================");

            if (!handleAuthFlow()) {
                disconnect();
                return;
            }

            handleMainMenu();

        } catch (IOException e) {
            System.out.println("[INFO] Client disconnected: " +
                    (currentUser != null ? currentUser.getUsername() : socket.getInetAddress()));
        } finally {
            disconnect();
        }
    }

    // Auth

    private boolean handleAuthFlow() throws IOException {
        while (running) {
            send("\n[AUTH MENU]");
            send("  1) Login");
            send("  2) Register");
            send("  -) Quit");
            send("Enter choice: ");

            String choice = readLine();
            if (choice == null || choice.equals("-")) {
                send("Goodbye!");
                return false;
            }

            switch (choice.trim()) {
                case "1":
                    if (handleLogin())
                        return true;
                    break;
                case "2":
                    if (handleRegister())
                        return true;
                    break;
                default:
                    send("Invalid option. Please enter 1, 2, or - to quit.");
            }
        }
        return false;
    }

    private boolean handleLogin() throws IOException {
        send("--- Login ---");
        send("Username: ");
        String username = readLine();
        if (username == null || username.equals("-"))
            return false;

        send("Password: ");
        String password = readLine();
        if (password == null || password.equals("-"))
            return false;

        AuthManager.LoginResult result = authManager.login(username.trim(), password.trim());
        send(result.message);

        if (result.isSuccess()) {
            currentUser = result.user;
            gameServer.registerClient(currentUser.getUsername(), this);
            return true;
        }
        return false;
    }

    private boolean handleRegister() throws IOException {
        send("--- Register ---");
        send("Full Name: ");
        String name = readLine();
        if (name == null || name.equals("-"))
            return false;

        // Check username i
        String username;
        while (true) {
            send("Username: ");
            username = readLine();
            if (username == null || username.equals("-"))
                return false;
            username = username.trim();

            if (authManager.getUser(username) != null) {
                send("ERROR 409: Username '" + username + "' is already taken.");

                // Generate suggestions
                String s1 = username + "_" + (int) (Math.random() * 900 + 100);
                String s2 = username + (int) (Math.random() * 900 + 100);
                String s3 = username.substring(0, 1).toUpperCase() + username.substring(1) + "_"
                        + (int) (Math.random() * 90 + 10);

                send("Suggested usernames:");
                send("  1) " + s1);
                send("  2) " + s2);
                send("  3) " + s3);
                send("  4) Enter your own");
                send("Enter 1-3 to pick a suggestion, or 4 to type your own: ");

                String pick = readLine();
                if (pick == null || pick.trim().equals("-"))
                    return false;

                switch (pick.trim()) {
                    case "1":
                        username = s1;
                        break;
                    case "2":
                        username = s2;
                        break;
                    case "3":
                        username = s3;
                        break;
                    default:
                        continue;
                }

                if (authManager.getUser(username) != null) {
                    send("That username is also taken. Please try again.");
                    continue;
                }
                break;
            } else {
                break;
            }
        }

        send("Password: ");
        String password = readLine();
        if (password == null || password.equals("-"))
            return false;

        AuthManager.LoginResult result = authManager.register(name.trim(), username, password.trim());
        send(result.message);

        if (result.isSuccess()) {
            currentUser = result.user;
            gameServer.registerClient(currentUser.getUsername(), this);
            return true;
        }
        return false;
    }

    // Main Menu

    private void handleMainMenu() throws IOException {
        while (running) {
            send("\n==============================================");
            send("  Main Menu - Hello, " + currentUser.getName() + "!");
            send("==============================================");
            send("  1) Play Single Player");
            send("  2) Play Multiplayer (Teams)");
            send("  3) View My Score History");
            send("  4) View Available Categories");
            send("  -) Quit");
            send("Enter choice: ");

            String choice = readLine();
            if (choice == null || choice.trim().equals("-")) {
                send("Thanks for playing! Goodbye, " + currentUser.getName() + "!");
                break;
            }

            switch (choice.trim()) {
                case "1":
                    handleSinglePlayerSetup();
                    break;
                case "2":
                    handleMultiplayerSetup();
                    break;
                case "3":
                    showScoreHistory();
                    break;
                case "4":
                    showCategories();
                    break;
                default:
                    send("Invalid option. Please try again.");
            }
        }
    }

    // 1 Player Setup
    private void handleSinglePlayerSetup() throws IOException {
        send("\n--- Single Player Setup ---");

        String category = selectCategory();
        if (category == null)
            return;

        String difficulty = selectDifficulty();
        if (difficulty == null)
            return;

        int maxAvailable = questionBank.getAvailableCount(category, difficulty);
        if (maxAvailable == 0) {
            send("No questions available for that category/difficulty. Try another.");
            return;
        }

        send("Available questions: " + maxAvailable);
        send("How many questions would you like? (1-" + maxAvailable + "): ");
        int numQuestions = readInt(1, maxAvailable);
        if (numQuestions == -1)
            return;

        GameSession session = gameServer.createSinglePlayerSession(
                currentUser, category, difficulty, numQuestions);
        if (session != null)
            session.start();
    }

    // Multiplayer

    private void handleMultiplayerSetup() throws IOException {
        send("\n--- Multiplayer Menu ---");
        send("  1) Create a new team");
        send("  2) Join an existing team");
        send("  3) Back");
        send("Enter choice: ");

        String choice = readLine();
        if (choice == null || choice.trim().equals("-") || choice.trim().equals("3"))
            return;

        switch (choice.trim()) {
            case "1":
                handleCreateTeam();
                break;
            case "2":
                handleJoinTeam();
                break;
            default:
                send("Invalid option.");
        }
    }

    private void handleCreateTeam() throws IOException {
        send("\n--- Create Team ---");
        send("Enter a unique team name: ");
        String teamName = readLine();
        if (teamName == null || teamName.trim().equals("-"))
            return;
        teamName = teamName.trim();

        if (gameServer.teamExists(teamName)) {
            send("ERROR: Team name '" + teamName + "' already exists. Choose another.");
            return;
        }

        String category = selectCategory();
        if (category == null)
            return;

        String difficulty = selectDifficulty();
        if (difficulty == null)
            return;

        int maxAvailable = questionBank.getAvailableCount(category, difficulty);
        if (maxAvailable == 0) {
            send("No questions available for that category/difficulty. Try another.");
            return;
        }

        send("How many questions? (1-" + maxAvailable + "): ");
        int numQuestions = readInt(1, maxAvailable);
        if (numQuestions == -1)
            return;

        send("Max players per team (1-" + gameServer.getMaxPlayersPerTeam() + "): ");
        int maxPlayers = readInt(1, gameServer.getMaxPlayersPerTeam());
        if (maxPlayers == -1)
            return;

        Team team = gameServer.createTeam(teamName, currentUser, this,
                category, difficulty, numQuestions, maxPlayers);
        send("Team '" + teamName + "' created! You are the team leader.");
        send("Waiting for the opposing team to join and be ready...");
        send("(Type 'start' when both teams are ready, or '-' to cancel)");

        team.waitForGame(this);
    }

    private void handleJoinTeam() throws IOException {
        send("\n--- Join Team ---");
        String[] teams = gameServer.getAvailableTeams();
        if (teams.length == 0) {
            send("No teams available to join right now.");
            return;
        }
        send("Available teams:");
        for (String t : teams) {
            send("  - " + t);
        }
        send("Enter team name to join: ");
        String teamName = readLine();
        if (teamName == null || teamName.trim().equals("-"))
            return;

        gameServer.joinTeam(teamName.trim(), currentUser, this);
    }

    // Score History

    private void showScoreHistory() {
        send("\n--- Your Score History ---");
        var history = currentUser.getScoreHistory();
        if (history.isEmpty()) {
            send("No games played yet.");
            return;
        }
        int start = Math.max(0, history.size() - 10);
        for (int i = start; i < history.size(); i++) {
            send("  " + history.get(i).toString());
        }
    }

    private void showCategories() {
        send("\n--- Available Categories ---");
        var categories = questionBank.getCategories();
        if (categories.isEmpty()) {
            send("No categories loaded.");
            return;
        }
        for (String cat : categories) {
            send("  - " + cat);
        }
    }

    private String selectCategory() throws IOException {
        var categories = questionBank.getCategories();
        if (categories.isEmpty()) {
            send("No categories available.");
            return null;
        }
        send("Select a category:");
        for (int i = 0; i < categories.size(); i++) {
            send("  " + (i + 1) + ") " + categories.get(i));
        }
        send("Enter number: ");
        int idx = readInt(1, categories.size());
        if (idx == -1)
            return null;
        return categories.get(idx - 1);
    }

    private String selectDifficulty() throws IOException {
        send("Select difficulty:");
        send("  1) Easy");
        send("  2) Medium");
        send("  3) Hard");
        send("Enter number: ");
        int idx = readInt(1, 3);
        if (idx == -1)
            return null;
        return new String[] { "easy", "medium", "hard" }[idx - 1];
    }

    private int readInt(int min, int max) throws IOException {
        while (true) {
            String line = readLine();
            if (line == null || line.trim().equals("-"))
                return -1;
            try {
                int val = Integer.parseInt(line.trim());
                if (val >= min && val <= max)
                    return val;
                send("Please enter a number between " + min + " and " + max + ": ");
            } catch (NumberFormatException e) {
                send("Invalid input. Please enter a number: ");
            }
        }
    }

    // I/p O/p
    public void send(String message) {
        if (out != null && !socket.isClosed()) {
            out.println(message);
        }
    }

    public String readLine() throws IOException {
        if (in == null)
            return null;
        try {
            String line = in.readLine();
            if (line != null && line.trim().equals("-")) {
                running = false;
                return "-";
            }
            return line;
        } catch (IOException e) {
            running = false;
            throw e;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public User getCurrentUser() {
        return currentUser;
    }

    private void disconnect() {
        running = false;
        if (currentUser != null) {
            gameServer.unregisterClient(currentUser.getUsername());
        }
        try {
            if (!socket.isClosed())
                socket.close();
        } catch (IOException ignored) {
        }
    }
}