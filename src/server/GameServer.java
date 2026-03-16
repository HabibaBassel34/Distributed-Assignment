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

    private AuthManager  authManager;
    private QuestionBank questionBank;

    private Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();

    private Map<String, Team> teams = new ConcurrentHashMap<>();


    private Set<Team> readyTeams = ConcurrentHashMap.newKeySet();


    private int minPlayersPerRoom;
    private int maxPlayersPerRoom;
    private int publicRoomQuestions;


    private boolean useLookupServer;
    private String  lookupServerHost;
    private int     lookupServerPort;

    private LookupClient lookupClient;

    private AdminStats adminStats = new AdminStats();

    private Map<String, GameRoom> gameRooms  = new ConcurrentHashMap<>();
    private int                   roomCounter = 0;

    private static final String CONFIG_FILE    = "data/config.txt";
    private static final String USERS_FILE     = "data/users.txt";
    private static final String SCORES_FILE    = "data/scores.txt";
    private static final String QUESTIONS_FILE = "data/questions.txt";

    public GameServer() throws IOException {
        System.out.println("==============================================");
        System.out.println("      Multiplayer Trivia Game Server         ");
        System.out.println("==============================================");
        loadServerData();
    }

    private void loadServerData() throws IOException {
        Map<String, String> config = FileLoader.loadConfig(CONFIG_FILE);

        this.port                   = Integer.parseInt(config.getOrDefault("server_port",              "5555"));
        this.maxPlayersPerTeam      = Integer.parseInt(config.getOrDefault("max_players_per_team",     "4"));
        this.minPlayersPerTeam      = Integer.parseInt(config.getOrDefault("min_players_per_team",     "1"));
        this.questionTimeoutSeconds = Integer.parseInt(config.getOrDefault("question_timeout_seconds", "15"));

        this.authManager  = new AuthManager(USERS_FILE, SCORES_FILE);

        List<Question> questions = FileLoader.loadQuestions(QUESTIONS_FILE);
        this.questionBank = new QuestionBank(questions);

        this.minPlayersPerRoom   = Integer.parseInt(config.getOrDefault("min_players_per_room",   "2"));
        this.maxPlayersPerRoom   = Integer.parseInt(config.getOrDefault("max_players_per_room",   "4"));
        this.publicRoomQuestions = Integer.parseInt(config.getOrDefault("public_room_questions",  "5"));

        this.useLookupServer  = Boolean.parseBoolean(config.getOrDefault("use_lookup_server",  "false"));
        this.lookupServerHost = config.getOrDefault("lookup_server_host", "localhost");
        this.lookupServerPort = Integer.parseInt(config.getOrDefault("lookup_server_port", "5556"));

        if (useLookupServer) {
            lookupClient = new LookupClient(lookupServerHost, lookupServerPort);
            System.out.println("[INFO] LookupServer ON -> " + lookupServerHost + ":" + lookupServerPort);
        } else {
            System.out.println("[INFO] LookupServer OFF -> using local QuestionBank");
        }

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



    public List<Question> getQuestions(String category, String difficulty, int count) {
        if (useLookupServer && lookupClient != null) {
            List<Question> fromServer = lookupClient.fetchQuestions(category, difficulty, count);
            if (!fromServer.isEmpty()) return fromServer;
            System.out.println("[WARN] LookupServer empty - falling back to local bank");
        }
        return questionBank.getQuestions(category, difficulty, count);
    }

    public List<Question> getRandomQuestions(int count) {
        if (useLookupServer && lookupClient != null) {
            List<Question> fromServer = lookupClient.fetchQuestions(null, null, count);
            if (!fromServer.isEmpty()) return fromServer;
            System.out.println("[WARN] LookupServer empty - falling back to local bank");
        }
        return questionBank.getRandomQuestions(count);
    }



    public void registerClient(String username, ClientHandler handler) {
        connectedClients.put(username, handler);
        adminStats.playerConnected();
        System.out.println("[INFO] '" + username + "' logged in. Online: " + connectedClients.size());
    }

    public void unregisterClient(String username) {
        connectedClients.remove(username);
        adminStats.playerDisconnected();
        System.out.println("[INFO] '" + username + "' disconnected. Online: " + connectedClients.size());
        User user = authManager.getUser(username);
        if (user != null) {
            for (Team team : teams.values()) {
                team.removeMember(user);
                if (team.getMemberCount() == 0)
                    teams.remove(team.getName());
            }
            for (GameRoom room : gameRooms.values()) {
                room.removeMember(user);
                if (room.getMemberCount() == 0) gameRooms.remove(room.getRoomId());
            }
        }
    }


    public GameSession createSinglePlayerSession(User user, String category,
            String difficulty, int numQuestions) {
        ClientHandler handler = connectedClients.get(user.getUsername());
        List<Question> questions = getQuestions(category, difficulty, numQuestions);
        if (questions.isEmpty()) {
            handler.send("[ERROR] No questions found for the selected options.");
            return null;
        }
        adminStats.recordQuestionsPlayed(questions.size());
        return new GameSession(List.of(handler), List.of(user),
                questions, questionTimeoutSeconds, "single", authManager);
    }

    public GameSession createRandomSession(User user, int numQuestions) {
        ClientHandler handler = connectedClients.get(user.getUsername());
        List<Question> questions = getRandomQuestions(numQuestions);
        if (questions.isEmpty()) {
            handler.send("[ERROR] No questions available.");
            return null;
        }
        adminStats.recordQuestionsPlayed(questions.size());
        return new GameSession(List.of(handler), List.of(user),
                questions, questionTimeoutSeconds, "random", authManager);
    }


    public boolean teamExists(String teamName) {
        return teams.containsKey(teamName);
    }

    public Team createTeam(String teamName, User creator, ClientHandler handler,
            String category, String difficulty, int numQuestions, int maxPlayers) {
        // Pass 'this' so Team can call back tryMatchTeam() and removeTeam()
        Team team = new Team(teamName, creator, handler, category, difficulty, numQuestions, maxPlayers, this);
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
        handler.send("You joined team '" + teamName + "'. Waiting for the leader to start...");
        handler.send("Type '-' to leave the team.");

        handler.setSoTimeout(200);
        try {
            while (!team.isGameStarted() && !team.isCancelled()) {
                try {
                    String input = handler.readLine();
                    if (input == null || input.equals("-")) {
                        // Player chose to leave while waiting
                        team.removeMember(user);
                        handler.send("You left the team.");
                        return;
                    }
                } catch (java.net.SocketTimeoutException e) {
                } catch (Exception e) {
                    team.removeMember(user);
                    return;
                }
            }
        } finally {
            handler.setSoTimeout(0);
        }

        if (team.isCancelled()) return;


        while (handler.isInGame()) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    public String[] getAvailableTeams() {
        return teams.values().stream()
                .filter(t -> !t.isGameStarted() && !t.isFull())
                .map(t -> t.getName() + " (" + t.getMemberCount() + "/" + t.getMaxPlayers() + " players)")
                .toArray(String[]::new);
    }

    public synchronized void tryMatchTeam(Team incomingTeam) {
        if (incomingTeam.isCancelled() || incomingTeam.isGameStarted()) return;

        if (!incomingTeam.isFull()) {
            incomingTeam.broadcast("Cannot start yet: your team needs "
                    + (incomingTeam.getMaxPlayers() - incomingTeam.getMemberCount())
                    + " more player(s). ("
                    + incomingTeam.getMemberCount() + "/" + incomingTeam.getMaxPlayers() + ")");
            return;
        }

        readyTeams.add(incomingTeam);
        System.out.println("[INFO] Team '" + incomingTeam.getName() + "' ready. Ready teams: " + readyTeams.size());

        for (Team opponent : readyTeams) {
            if (opponent == incomingTeam) continue;
            if (opponent.isCancelled() || opponent.isGameStarted()) continue;

            if (opponent.getMemberCount() == incomingTeam.getMemberCount()) {
                readyTeams.remove(incomingTeam);
                readyTeams.remove(opponent);
                System.out.println("[INFO] Matched: '" + incomingTeam.getName() + "' vs '" + opponent.getName() + "'");
                startTeamGame(incomingTeam, opponent);
                return;
            } else {
                String msg = "Cannot start: unequal team sizes. "
                        + incomingTeam.getName() + "=" + incomingTeam.getMemberCount()
                        + " vs " + opponent.getName() + "=" + opponent.getMemberCount()
                        + ". Fill your team and try again.";
                incomingTeam.broadcast(msg);
                opponent.broadcast(msg);
                readyTeams.remove(incomingTeam);
                return;
            }
        }

        incomingTeam.broadcast("[TEAM " + incomingTeam.getName() + "] Waiting for an opposing team...");
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

        List<ClientHandler> allHandlers = new ArrayList<>(teamA.getMemberHandlers());
        allHandlers.addAll(teamB.getMemberHandlers());
        List<User> allPlayers = new ArrayList<>(teamA.getMembers());
        allPlayers.addAll(teamB.getMembers());


        for (ClientHandler h : allHandlers) {
            h.setInGame(true);
        }

        teamA.setGameStarted(true);
        teamB.setGameStarted(true);
        teams.remove(teamA.getName());
        teams.remove(teamB.getName());


        String settingsMsg = "Game settings by team '" + teamA.getName() + "': "
                + "Category=" + teamA.getCategory()
                + " | Difficulty=" + teamA.getDifficulty()
                + " | Questions=" + teamA.getNumQuestions();
        teamA.broadcast(settingsMsg);
        teamB.broadcast(settingsMsg);

        List<Question> questions = getQuestions(
                teamA.getCategory(), teamA.getDifficulty(), teamA.getNumQuestions());

        adminStats.recordQuestionsPlayed(questions.size());

        GameSession session = new GameSession(allHandlers, allPlayers, questions,
                questionTimeoutSeconds, "multiplayer", authManager);

        new Thread(() -> {

            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
            session.start();
            for (ClientHandler h : allHandlers) {
                h.setInGame(false);
            }
        }).start();
        return true;
    }


    public void removeTeam(String teamName) {
        teams.remove(teamName);
        readyTeams.removeIf(t -> t.getName().equals(teamName));
        System.out.println("[INFO] Team '" + teamName + "' removed.");
    }



    public void joinPublicRoom(User user, ClientHandler handler) {
        GameRoom room = null;
        for (GameRoom r : gameRooms.values()) {
            if (!r.isGameStarted() && !r.isFull()) {
                room = r;
                break;
            }
        }


        if (room == null) {
            roomCounter++;
            String id = "Room-" + roomCounter;
            room = new GameRoom(id, minPlayersPerRoom, maxPlayersPerRoom);
            gameRooms.put(id, room);
            System.out.println("[INFO] Created public " + id);
        }

        if (!room.addMember(user, handler)) {
            handler.send("ERROR: Could not join a room right now. Please try again.");
            return;
        }

        handler.send("Joined " + room.getRoomId() + " ("
                + room.getMemberCount() + "/" + room.getMaxPlayers() + " players).");
        handler.send("Game starts when room is full, or type 'start' once "
                + minPlayersPerRoom + "+ players are present. Type '-' to leave.");

        final GameRoom finalRoom = room;


        if (finalRoom.isFull()) {
            launchRoomGame(finalRoom);
            return;
        }


        waitForRoomStart(user, handler, finalRoom);


        while (handler.isInGame()) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }


    private void waitForRoomStart(User user, ClientHandler handler, GameRoom room) {
        handler.setSoTimeout(200);
        try {
            while (!room.isGameStarted()) {
                try {
                    String input = handler.readLine();
                    if (input == null || input.equals("-")) {
                        room.removeMember(user);
                        room.broadcast("[INFO] " + user.getName() + " left the room.");
                        if (room.getMemberCount() == 0) gameRooms.remove(room.getRoomId());
                        return;
                    }
                    if (input.equalsIgnoreCase("start")) {
                        if (room.hasMinPlayers()) {
                            launchRoomGame(room);
                            return;
                        } else {
                            handler.send("Need at least " + room.getMinPlayers()
                                    + " players. Currently: " + room.getMemberCount());
                        }
                    }
                    if (room.isFull()) {
                        launchRoomGame(room);
                        return;
                    }
                } catch (java.net.SocketTimeoutException e) {

                    continue;
                } catch (Exception e) {

                    room.removeMember(user);
                    if (room.getMemberCount() == 0) gameRooms.remove(room.getRoomId());
                    return;
                }
            }
        } finally {
            handler.setSoTimeout(0);
        }
    }


    private void launchRoomGame(GameRoom room) {
        if (room.isGameStarted()) return;

        List<Question> questions = getRandomQuestions(publicRoomQuestions);


        List<ClientHandler> roomHandlers = new ArrayList<>(room.getMemberHandlers());
        List<User>          roomMembers  = new ArrayList<>(room.getMembers());


        for (ClientHandler h : roomHandlers) {
            h.setInGame(true);
        }

        room.setGameStarted(true);
        gameRooms.remove(room.getRoomId());

        GameSession session = new GameSession(
                roomHandlers, roomMembers,
                questions, questionTimeoutSeconds, "public", authManager);


        adminStats.recordQuestionsPlayed(questions.size());

        room.broadcast("[INFO] Starting game now...");
        new Thread(() -> {

            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
            session.start();

            for (ClientHandler h : roomHandlers) {
                h.setInGame(false);
            }
        }).start();
    }


    public int          getMaxPlayersPerTeam() { return maxPlayersPerTeam; }
    public int          getMinPlayersPerTeam() { return minPlayersPerTeam; }
    public QuestionBank getQuestionBank()      { return questionBank; }
    public AdminStats   getAdminStats()        { return adminStats; }
    public AuthManager  getAuthManager()       { return authManager; }
    // Fix: always read from the actual map so the count never drifts
    public int          getConnectedCount()    { return connectedClients.size(); }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    public static void main(String[] args) {
        try {
            GameServer server = new GameServer();
            server.start();
        } catch (IOException e) {
            System.out.println("[FATAL] Could not start server: " + e.getMessage());
        }
    }
}
