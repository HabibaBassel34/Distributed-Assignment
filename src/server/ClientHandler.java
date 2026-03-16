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


    private volatile boolean inGame = false;

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


            if (currentUser.getUsername().equals("admin")) {
                showAdminPanel();
            } else {
                handleMainMenu();
            }

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

        String username;
        while (true) {
            send("Username: ");
            username = readLine();
            if (username == null || username.equals("-"))
                return false;
            username = username.trim();

            if (authManager.getUser(username) != null) {
                send("ERROR 409: Username '" + username + "' is already taken.");

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
                    case "1": username = s1; break;
                    case "2": username = s2; break;
                    case "3": username = s3; break;
                    default: continue;
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


    private void handleMainMenu() throws IOException {
        while (running) {

            if (inGame) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                continue;
            }

            send("\n==============================================");
            send("  Main Menu - Hello, " + currentUser.getName() + "!");
            send("==============================================");
            send("  1) Play Single Player");
            send("  2) Play Multiplayer (Teams)");
            send("  3) Join Public Game Room");
            send("  4) Play Random Trivia");
            send("  5) View My Score History");
            send("  6) View Available Categories");
            send("  -) Quit");
            send("Enter choice: ");


            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            if (inGame) continue;

            String choice = readLine();
            if (choice == null || choice.trim().equals("-")) {
                send("Thanks for playing! Goodbye, " + currentUser.getName() + "!");
                break;
            }

            if (inGame) continue;

            switch (choice.trim()) {
                case "1":
                    handleSinglePlayerSetup();
                    break;
                case "2":
                    handleMultiplayerSetup();
                    break;
                case "3":
                    gameServer.joinPublicRoom(currentUser, this);
                    break;
                case "4":
                    handleRandomTrivia();
                    break;
                case "5":
                    showScoreHistory();
                    break;
                case "6":
                    showCategories();
                    break;
                default:
                    send("Invalid option. Please try again.");
            }
        }
    }



    private void showAdminPanel() throws IOException {
        while (running) {
            send("\n==============================================");
            send("           ADMIN PANEL                      ");
            send("==============================================");
            send("  1) Total Connected Players");
            send("  2) Player With Most Wins");
            send("  3) Total Questions Played (this session)");
            send("  4) Highest Score Ever Recorded");
            send("  -) Logout");
            send("Enter choice: ");

            String choice = readLine();
            if (choice == null || choice.trim().equals("-")) {
                send("Admin logged out.");
                break;
            }

            AdminStats stats = gameServer.getAdminStats();

            switch (choice.trim()) {
                case "1":
                    send("Total connected players: " + gameServer.getConnectedCount());
                    break;
                case "2":
                    send("Player with most wins: "
                            + stats.getMostWins(gameServer.getAuthManager().getAllUsers()));
                    break;
                case "3":
                    send("Total questions played this session: " + stats.getTotalQuestionsPlayed());
                    break;
                case "4":
                    send("Highest score ever: "
                            + stats.getHighestScore(gameServer.getAuthManager().getAllUsers()) + " pts");
                    break;
                default:
                    send("Invalid option. Enter 1-4 or - to logout.");
            }
        }
    }


    private void handleSinglePlayerSetup() throws IOException {
        send("\n--- Single Player Setup ---");

        String category = selectCategory();
        if (category == null) return;

        String difficulty = selectDifficulty();
        if (difficulty == null) return;

        int maxAvailable = questionBank.getAvailableCount(category, difficulty);
        if (maxAvailable == 0) {
            send("No questions available for that category/difficulty. Try another.");
            return;
        }

        send("Available questions: " + maxAvailable);
        send("How many questions would you like? (1-" + maxAvailable + "): ");
        int numQuestions = readInt(1, maxAvailable);
        if (numQuestions == -1) return;

        GameSession session = gameServer.createSinglePlayerSession(
                currentUser, category, difficulty, numQuestions);
        if (session != null) {
            setInGame(true);
            session.start();
            setInGame(false);
        }
    }



    private void handleRandomTrivia() throws IOException {
        send("\n--- Random Trivia ---");
        send("Questions will be picked randomly from all categories and difficulties.");

        int max = questionBank.getTotalCount();
        if (max == 0) {
            send("No questions available.");
            return;
        }

        send("How many questions? (1-" + max + "): ");
        int numQuestions = readInt(1, max);
        if (numQuestions == -1) return;

        GameSession session = gameServer.createRandomSession(currentUser, numQuestions);
        if (session != null) {
            setInGame(true);
            session.start();
            setInGame(false);
        }
    }


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
            case "1": handleCreateTeam(); break;
            case "2": handleJoinTeam(); break;
            default: send("Invalid option.");
        }
    }

    private void handleCreateTeam() throws IOException {
        send("\n--- Create Team ---");
        send("Enter a unique team name: ");
        String teamName = readLine();
        if (teamName == null || teamName.trim().equals("-")) return;
        teamName = teamName.trim();

        if (gameServer.teamExists(teamName)) {
            send("ERROR: Team name '" + teamName + "' already exists. Choose another.");
            return;
        }

        String category = selectCategory();
        if (category == null) return;

        String difficulty = selectDifficulty();
        if (difficulty == null) return;

        int maxAvailable = questionBank.getAvailableCount(category, difficulty);
        if (maxAvailable == 0) {
            send("No questions available for that category/difficulty. Try another.");
            return;
        }

        send("How many questions? (1-" + maxAvailable + "): ");
        int numQuestions = readInt(1, maxAvailable);
        if (numQuestions == -1) return;

        send("Max players per team (1-" + gameServer.getMaxPlayersPerTeam() + "): ");
        int maxPlayers = readInt(1, gameServer.getMaxPlayersPerTeam());
        if (maxPlayers == -1) return;

        Team team = gameServer.createTeam(teamName, currentUser, this,
                category, difficulty, numQuestions, maxPlayers);
        send("Team '" + teamName + "' created! You are the team leader.");
        send("Waiting for " + (maxPlayers - 1) + " more player(s) to join your team...");
        send("Once your team is full, type 'start' to look for an opponent. Or '-' to cancel.");

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
        if (teamName == null || teamName.trim().equals("-")) return;

        gameServer.joinTeam(teamName.trim(), currentUser, this);
    }


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
        if (idx == -1) return null;
        return categories.get(idx - 1);
    }

    private String selectDifficulty() throws IOException {
        send("Select difficulty:");
        send("  1) Easy");
        send("  2) Medium");
        send("  3) Hard");
        send("Enter number: ");
        int idx = readInt(1, 3);
        if (idx == -1) return null;
        return new String[]{"easy", "medium", "hard"}[idx - 1];
    }

    private int readInt(int min, int max) throws IOException {
        while (true) {
            String line = readLine();
            if (line == null || line.trim().equals("-")) return -1;
            try {
                int val = Integer.parseInt(line.trim());
                if (val >= min && val <= max) return val;
                send("Please enter a number between " + min + " and " + max + ": ");
            } catch (NumberFormatException e) {
                // Feature 11: Error handling - invalid non-numeric input
                send("Invalid input '" + line.trim() + "'. Please enter a number: ");
            }
        }
    }


    public boolean isConnected() {
        return running && socket != null && !socket.isClosed();
    }

    public void send(String message) {
        if (out != null && !socket.isClosed()) {
            out.println(message);
        }
    }

    public String readLine() throws IOException {
        if (in == null) return null;
        try {
            String line = in.readLine();
            if (line != null && line.trim().equals("-")) {
                running = false;
                return "-";
            }
            return line;
        } catch (java.net.SocketTimeoutException e) {

            throw e;
        } catch (IOException e) {
            running = false;
            throw e;
        }
    }


    public void setSoTimeout(int millis) {
        try { socket.setSoTimeout(millis); } catch (IOException ignored) {}
    }


    public void setInGame(boolean value) {
        this.inGame = value;
    }

    public boolean isInGame() {
        return inGame;
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
            if (!socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }
}
