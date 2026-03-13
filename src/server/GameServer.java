package server;

import model.Question;
import model.User;
import util.FileLoader;
import util.QuestionBank;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GameServer {
    private int port;
    private int maxPlayersPerTeam;
    private int minPlayersPerTeam;
    private int questionTimeoutSeconds;

    private AuthManager authManager;
    private QuestionBank questionBank;

    private Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
    private Map<String, Team> teams = new ConcurrentHashMap<>();

    private static final String CONFIG_FILE = "data/config.txt";
    private static final String USERS_FILE = "data/users.txt";
    private static final String SCORES_FILE = "data/scores.txt";
    private static final String QUESTIONS_FILE = "data/questions.txt";

    public GameServer() throws IOException {
        System.out.println("==============================================");
        System.out.println("      Multiplayer Trivia Game Server         ");
        System.out.println("==============================================");
        loadServerData();
    }

    private void loadServerData() throws IOException {
        Map<String, String> config = FileLoader.loadConfig(CONFIG_FILE);
        this.port = Integer.parseInt(config.getOrDefault("server_port", "5555"));
        this.maxPlayersPerTeam = Integer.parseInt(config.getOrDefault("max_players_per_team", "4"));
        this.minPlayersPerTeam = Integer.parseInt(config.getOrDefault("min_players_per_team", "1"));
        this.questionTimeoutSeconds = Integer.parseInt(config.getOrDefault("question_timeout_seconds", "15"));

        this.authManager = new AuthManager(USERS_FILE, SCORES_FILE);

        List<Question> questions = FileLoader.loadQuestions(QUESTIONS_FILE);
        this.questionBank = new QuestionBank(questions);

        System.out.println("[INFO] Server ready. Port: " + port
                + " | Max players/team: " + maxPlayersPerTeam
                + " | Question timeout: " + questionTimeoutSeconds + "s");
    }

    public void start() {
        System.out.println("[INFO] Listening on port " + port + "...\n");

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {

                Socket socket = serverSocket.accept();
                System.out.println("[INFO] New connection from " + socket.getInetAddress());

                ClientHandler handler = new ClientHandler(socket, authManager, questionBank, this);
                Thread t = new Thread(handler);
                t.start();
                System.out.println("[INFO] Thread #" + t.getId() + " started for client.");
            }
        } catch (IOException e) {
            System.out.println("[ERROR] Server failed: " + e.getMessage());
        }
    }

    // Client Registry

    public void registerClient(String username, ClientHandler handler) {
        connectedClients.put(username, handler);
        System.out.println("[INFO] '" + username + "' logged in. Online: " + connectedClients.size());
    }

    public void unregisterClient(String username) {
        connectedClients.remove(username);
        System.out.println("[INFO] '" + username + "' disconnected. Online: " + connectedClients.size());
        User user = authManager.getUser(username);
        if (user != null) {
            for (Team team : teams.values()) {
                team.removeMember(user);
                if (team.getMemberCount() == 0)
                    teams.remove(team.getName());
            }
        }
    }

    // 1 Player

    public GameSession createSinglePlayerSession(User user, String category,
            String difficulty, int numQuestions) {
        ClientHandler handler = connectedClients.get(user.getUsername());
        List<Question> questions = questionBank.getQuestions(category, difficulty, numQuestions);
        if (questions.isEmpty()) {
            handler.send("[ERROR] No questions found for the selected options.");
            return null;
        }
        return new GameSession(
                List.of(handler), List.of(user),
                questions, questionTimeoutSeconds,
                "single", authManager);
    }

    // Teams

    public boolean teamExists(String teamName) {
        return teams.containsKey(teamName);
    }

    public Team createTeam(String teamName, User creator, ClientHandler handler,
            String category, String difficulty, int numQuestions, int maxPlayers) {
        Team team = new Team(teamName, creator, handler, category, difficulty, numQuestions, maxPlayers);
        teams.put(teamName, team);
        System.out.println("[INFO] Team created: " + teamName + " by " + creator.getUsername());
        return team;
    }

    public void joinTeam(String teamName, User user, ClientHandler handler) {
        Team team = teams.get(teamName);
        if (team == null) {
            handler.send("ERROR: Team '" + teamName + "' not found.");
            return;
        }
        if (team.isGameStarted()) {
            handler.send("ERROR: Game already started.");
            return;
        }
        if (!team.addMember(user, handler)) {
            handler.send("ERROR: Team '" + teamName + "' is full (" + team.getMaxPlayers() + " max).");
            return;
        }
        handler.send("You joined team '" + teamName + "'. Waiting for game to start...");
    }

    public String[] getAvailableTeams() {
        return teams.values().stream()
                .filter(t -> !t.isGameStarted() && !t.isFull())
                .map(Team::getName)
                .toArray(String[]::new);
    }

    public boolean startTeamGame(Team teamA, Team teamB) {
        if (teamA.getMemberCount() != teamB.getMemberCount()) {
            String msg = "ERROR: Teams must have equal players. "
                    + teamA.getName() + "=" + teamA.getMemberCount()
                    + " vs " + teamB.getName() + "=" + teamB.getMemberCount();
            teamA.broadcast(msg);
            teamB.broadcast(msg);
            return false;
        }
        teamA.setGameStarted(true);
        teamB.setGameStarted(true);
        teams.remove(teamA.getName());
        teams.remove(teamB.getName());

        List<ClientHandler> allHandlers = new ArrayList<>(teamA.getMemberHandlers());
        allHandlers.addAll(teamB.getMemberHandlers());
        List<User> allPlayers = new ArrayList<>(teamA.getMembers());
        allPlayers.addAll(teamB.getMembers());

        List<Question> questions = questionBank.getQuestions(
                teamA.getCategory(), teamA.getDifficulty(), teamA.getNumQuestions());

        GameSession session = new GameSession(
                allHandlers, allPlayers, questions,
                questionTimeoutSeconds, "multiplayer", authManager);

        // Start game
        Thread t = new Thread(session::start);
        t.start();
        return true;
    }

    public int getMaxPlayersPerTeam() {
        return maxPlayersPerTeam;
    }

    public int getMinPlayersPerTeam() {
        return minPlayersPerTeam;
    }

    public static void main(String[] args) {
        try {
            GameServer server = new GameServer();
            server.start();
        } catch (IOException e) {
            System.out.println("[FATAL] Could not start server: " + e.getMessage());
        }
    }
}