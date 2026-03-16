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

    // --- existing config ---
    private int port;
    private int maxPlayersPerTeam;
    private int minPlayersPerTeam;
    private int questionTimeoutSeconds;

    private AuthManager  authManager;
    private QuestionBank questionBank;

    // Connected clients map: username -> handler
    private Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();

    // Active teams waiting for players / opponents
    private Map<String, Team> teams = new ConcurrentHashMap<>();

    // Teams whose leader typed "start" - waiting for an opponent
    // ConcurrentHashMap.newKeySet() gives a thread-safe Set
    private Set<Team> readyTeams = ConcurrentHashMap.newKeySet();

    // --- NEW: public room config (from config.txt) ---
    // ASSUMPTION: defaults are min=2, max=4, 5 questions if keys are missing
    private int minPlayersPerRoom;
    private int maxPlayersPerRoom;
    private int publicRoomQuestions;

    // --- NEW: lookup server config (from config.txt) ---
    // ASSUMPTION: disabled by default; set use_lookup_server=true to enable
    private boolean useLookupServer;
    private String  lookupServerHost;
    private int     lookupServerPort;

    // --- NEW: LookupClient - null when feature is disabled ---
    private LookupClient lookupClient;

    // --- NEW: admin stats tracker ---
    private AdminStats adminStats = new AdminStats();

    // --- NEW: active public game rooms (runtime only, cleared on shutdown) ---
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

        // existing keys
        this.port                   = Integer.parseInt(config.getOrDefault("server_port",              "5555"));
        this.maxPlayersPerTeam      = Integer.parseInt(config.getOrDefault("max_players_per_team",     "4"));
        this.minPlayersPerTeam      = Integer.parseInt(config.getOrDefault("min_players_per_team",     "1"));
        this.questionTimeoutSeconds = Integer.parseInt(config.getOrDefault("question_timeout_seconds", "15"));

        this.authManager  = new AuthManager(USERS_FILE, SCORES_FILE);

        List<Question> questions = FileLoader.loadQuestions(QUESTIONS_FILE);
        this.questionBank = new QuestionBank(questions);

        // NEW: public room keys
        this.minPlayersPerRoom   = Integer.parseInt(config.getOrDefault("min_players_per_room",   "2"));
        this.maxPlayersPerRoom   = Integer.parseInt(config.getOrDefault("max_players_per_room",   "4"));
        this.publicRoomQuestions = Integer.parseInt(config.getOrDefault("public_room_questions",  "5"));

        // NEW: lookup server keys
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

    // -----------------------------------------------------------------------
    // Main server loop
    // -----------------------------------------------------------------------

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

    // -----------------------------------------------------------------------
    // NEW: central question fetcher
    // Tries LookupServer first; falls back to local QuestionBank if disabled
    // or if LookupServer returns nothing (e.g. it's down).
    // ASSUMPTION: empty list from LookupServer = server is down or no match found.
    // -----------------------------------------------------------------------

    public List<Question> getQuestions(String category, String difficulty, int count) {
        if (useLookupServer && lookupClient != null) {
            List<Question> fromServer = lookupClient.fetchQuestions(category, difficulty, count);
            if (!fromServer.isEmpty()) return fromServer;
            System.out.println("[WARN] LookupServer empty - falling back to local bank");
        }
        return questionBank.getQuestions(category, difficulty, count);
    }

    // Random trivia: null params = no filter = all categories and difficulties mixed
    public List<Question> getRandomQuestions(int count) {
        if (useLookupServer && lookupClient != null) {
            List<Question> fromServer = lookupClient.fetchQuestions(null, null, count);
            if (!fromServer.isEmpty()) return fromServer;
            System.out.println("[WARN] LookupServer empty - falling back to local bank");
        }
        return questionBank.getRandomQuestions(count);
    }

    // -----------------------------------------------------------------------
    // Client registry - updated to track admin stats
    // -----------------------------------------------------------------------

    public void registerClient(String username, ClientHandler handler) {
        connectedClients.put(username, handler);
        adminStats.playerConnected(); // NEW: track for admin panel
        System.out.println("[INFO] '" + username + "' logged in. Online: " + connectedClients.size());
    }

    public void unregisterClient(String username) {
        connectedClients.remove(username);
        adminStats.playerDisconnected(); // NEW: track for admin panel
        System.out.println("[INFO] '" + username + "' disconnected. Online: " + connectedClients.size());
        User user = authManager.getUser(username);
        if (user != null) {
            for (Team team : teams.values()) {
                team.removeMember(user);
                if (team.getMemberCount() == 0)
                    teams.remove(team.getName());
            }
            // NEW: also clean up any public room the player was waiting in
            for (GameRoom room : gameRooms.values()) {
                room.removeMember(user);
                if (room.getMemberCount() == 0) gameRooms.remove(room.getRoomId());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Single Player
    // -----------------------------------------------------------------------

    public GameSession createSinglePlayerSession(User user, String category,
            String difficulty, int numQuestions) {
        ClientHandler handler = connectedClients.get(user.getUsername());
        List<Question> questions = getQuestions(category, difficulty, numQuestions);
        if (questions.isEmpty()) {
            handler.send("[ERROR] No questions found for the selected options.");
            return null;
        }
        // Fix: count questions for admin stats
        adminStats.recordQuestionsPlayed(questions.size());
        return new GameSession(List.of(handler), List.of(user),
                questions, questionTimeoutSeconds, "single", authManager);
    }

    // NEW: Random trivia session - Additional Feature 3
    public GameSession createRandomSession(User user, int numQuestions) {
        ClientHandler handler = connectedClients.get(user.getUsername());
        List<Question> questions = getRandomQuestions(numQuestions);
        if (questions.isEmpty()) {
            handler.send("[ERROR] No questions available.");
            return null;
        }
        // Fix: count questions for admin stats
        adminStats.recordQuestionsPlayed(questions.size());
        // gameType = "random" so the score record is labelled differently
        return new GameSession(List.of(handler), List.of(user),
                questions, questionTimeoutSeconds, "random", authManager);
    }

    // -----------------------------------------------------------------------
    // Teams
    // -----------------------------------------------------------------------

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

        // Block here with a socket timeout so we can detect when gameStarted=true.
        // Without this, handleMainMenu() would run immediately and compete with
        // GameSession for this player's socket input once the leader starts the game.
        // ASSUMPTION: same 200ms timeout as public rooms for consistency.
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
                    // Any other input while waiting is ignored
                } catch (java.net.SocketTimeoutException e) {
                    continue; // loop and re-check team.isGameStarted()
                } catch (Exception e) {
                    team.removeMember(user);
                    return;
                }
            }
        } finally {
            handler.setSoTimeout(0); // always reset so socket works normally
        }

        // If game was cancelled (leader quit), just return to menu
        if (team.isCancelled()) return;

        // Spin until the game finishes.
        // inGame was set to true by startTeamGame() BEFORE gameStarted=true,
        // so this spin-wait will always see inGame=true here.
        while (handler.isInGame()) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    public String[] getAvailableTeams() {
        // Only show teams that still have open spots (not full, not started)
        return teams.values().stream()
                .filter(t -> !t.isGameStarted() && !t.isFull())
                .map(t -> t.getName() + " (" + t.getMemberCount() + "/" + t.getMaxPlayers() + " players)")
                .toArray(String[]::new);
    }

    // Called by Team.waitForGame() when the leader types "start".
    // Adds this team to the ready set, then tries to find an opponent.
    // ASSUMPTION: Both teams must be full and have equal member counts to start.
    public synchronized void tryMatchTeam(Team incomingTeam) {
        if (incomingTeam.isCancelled() || incomingTeam.isGameStarted()) return;

        // Team must be full before it can be matched (ensures equal sizes)
        if (!incomingTeam.isFull()) {
            incomingTeam.broadcast("Cannot start yet: your team needs "
                    + (incomingTeam.getMaxPlayers() - incomingTeam.getMemberCount())
                    + " more player(s). ("
                    + incomingTeam.getMemberCount() + "/" + incomingTeam.getMaxPlayers() + ")");
            return;
        }

        readyTeams.add(incomingTeam);
        System.out.println("[INFO] Team '" + incomingTeam.getName() + "' ready. Ready teams: " + readyTeams.size());

        // Look for another ready team with the same size
        for (Team opponent : readyTeams) {
            if (opponent == incomingTeam) continue;
            if (opponent.isCancelled() || opponent.isGameStarted()) continue;

            if (opponent.getMemberCount() == incomingTeam.getMemberCount()) {
                // Match found - remove both from ready set and start
                readyTeams.remove(incomingTeam);
                readyTeams.remove(opponent);
                System.out.println("[INFO] Matched: '" + incomingTeam.getName() + "' vs '" + opponent.getName() + "'");
                startTeamGame(incomingTeam, opponent);
                return;
            } else {
                // Sizes don't match - inform both and remove the incoming team
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

        // No opponent yet - stay in ready set and wait
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

        // Snapshot handlers before setting any flags
        List<ClientHandler> allHandlers = new ArrayList<>(teamA.getMemberHandlers());
        allHandlers.addAll(teamB.getMemberHandlers());
        List<User> allPlayers = new ArrayList<>(teamA.getMembers());
        allPlayers.addAll(teamB.getMembers());

        // CRITICAL ORDER FIX: set inGame=true for ALL players BEFORE setGameStarted(true).
        // Leaders are spin-waiting in Team.waitForGame() on gameStarted.
        // Members are blocking in joinTeam() on gameStarted.
        // Both check isInGame() immediately after seeing gameStarted=true —
        // inGame must already be true or they escape to the main menu.
        for (ClientHandler h : allHandlers) {
            h.setInGame(true);
        }

        teamA.setGameStarted(true);
        teamB.setGameStarted(true);
        teams.remove(teamA.getName());
        teams.remove(teamB.getName());

        // Inform both teams whose settings are used
        // ASSUMPTION: teamA (first to type "start") sets the game parameters
        String settingsMsg = "Game settings by team '" + teamA.getName() + "': "
                + "Category=" + teamA.getCategory()
                + " | Difficulty=" + teamA.getDifficulty()
                + " | Questions=" + teamA.getNumQuestions();
        teamA.broadcast(settingsMsg);
        teamB.broadcast(settingsMsg);

        // Use getQuestions() so LookupServer is used when enabled
        List<Question> questions = getQuestions(
                teamA.getCategory(), teamA.getDifficulty(), teamA.getNumQuestions());

        // Fix: count questions for admin stats
        adminStats.recordQuestionsPlayed(questions.size());

        GameSession session = new GameSession(allHandlers, allPlayers, questions,
                questionTimeoutSeconds, "multiplayer", authManager);

        new Thread(() -> {
            // 350ms delay: members in joinTeam() have a 200ms read timeout,
            // so by 350ms everyone has exited their wait loop and no thread
            // competes with GameSession for the socket.
            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
            session.start();
            // Game over - release all players back to the main menu
            for (ClientHandler h : allHandlers) {
                h.setInGame(false);
            }
        }).start();
        return true;
    }

    // Removes a team from both the teams map and the readyTeams set.
    // Called by Team.waitForGame() when the leader cancels or disconnects.
    public void removeTeam(String teamName) {
        teams.remove(teamName);
        readyTeams.removeIf(t -> t.getName().equals(teamName));
        System.out.println("[INFO] Team '" + teamName + "' removed.");
    }

    // -----------------------------------------------------------------------
    // NEW: Public Game Room - Additional Feature 2
    // Players join a random public room; game starts when the room is full
    // or when a player types "start" and min players are present.
    // ASSUMPTION: Public rooms use random questions since players are strangers.
    // -----------------------------------------------------------------------

    public void joinPublicRoom(User user, ClientHandler handler) {
        // Find an open room that isn't full and hasn't started yet
        GameRoom room = null;
        for (GameRoom r : gameRooms.values()) {
            if (!r.isGameStarted() && !r.isFull()) {
                room = r;
                break;
            }
        }

        // No room found - create a new one
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

        // Auto-start immediately if room is now full
        if (finalRoom.isFull()) {
            launchRoomGame(finalRoom);
            return;
        }

        // Otherwise wait for more players or a "start" command
        waitForRoomStart(user, handler, finalRoom);

        // Fix: block here until the game finishes.
        // launchRoomGame() sets inGame=true before starting the session thread,
        // so the main menu loop won't run until the game is completely done.
        while (handler.isInGame()) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
    }

    // Blocks on this handler's input until the player types "start" or '-' or disconnects.
    // Uses a 200ms socket timeout so every player's loop can detect room.isGameStarted()
    // quickly after another player types "start". Without this, a player blocked inside
    // readLine() would compete with the GameSession for the same socket input.
    // ASSUMPTION: 200ms is short enough to react quickly but not wasteful on CPU.
    private void waitForRoomStart(User user, ClientHandler handler, GameRoom room) {
        handler.setSoTimeout(200); // short timeout so we can check gameStarted periodically
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
                    // Timeout fired - loop back and check room.isGameStarted() at the top.
                    // This is how we detect when another player started the game.
                    continue;
                } catch (Exception e) {
                    // Real disconnect or error
                    room.removeMember(user);
                    if (room.getMemberCount() == 0) gameRooms.remove(room.getRoomId());
                    return;
                }
            }
        } finally {
            // Always reset the timeout so the socket works normally after this
            handler.setSoTimeout(0);
        }
    }

    // Starts the GameSession for everyone in the room
    private void launchRoomGame(GameRoom room) {
        if (room.isGameStarted()) return; // guard against double-launch

        List<Question> questions = getRandomQuestions(publicRoomQuestions);

        // Snapshot lists BEFORE setting any flags
        List<ClientHandler> roomHandlers = new ArrayList<>(room.getMemberHandlers());
        List<User>          roomMembers  = new ArrayList<>(room.getMembers());

        // CRITICAL ORDER FIX: set inGame=true for ALL players BEFORE setGameStarted(true).
        // Players in waitForRoomStart() exit the loop the moment they see gameStarted=true,
        // then immediately check isInGame(). If inGame wasn't set yet, they skip the
        // spin-wait and the main menu appears while the game is still running.
        for (ClientHandler h : roomHandlers) {
            h.setInGame(true);
        }

        // Now it is safe to set gameStarted - anyone who detects it will also see inGame=true
        room.setGameStarted(true);
        gameRooms.remove(room.getRoomId());

        GameSession session = new GameSession(
                roomHandlers, roomMembers,
                questions, questionTimeoutSeconds, "public", authManager);

        // Fix: count questions for admin stats
        adminStats.recordQuestionsPlayed(questions.size());

        room.broadcast("[INFO] Starting game now...");
        new Thread(() -> {
            // Wait 350ms before reading any answers.
            // Players in waitForRoomStart() have a 200ms read timeout, so by 350ms
            // all of them will have exited that loop and no thread competes for the socket.
            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
            session.start();
            // Game over - release all players back to the main menu
            for (ClientHandler h : roomHandlers) {
                h.setInGame(false);
            }
        }).start();
    }

    // -----------------------------------------------------------------------
    // Getters
    // -----------------------------------------------------------------------

    public int          getMaxPlayersPerTeam() { return maxPlayersPerTeam; }
    public int          getMinPlayersPerTeam() { return minPlayersPerTeam; }
    public QuestionBank getQuestionBank()      { return questionBank; }
    public AdminStats   getAdminStats()        { return adminStats; }   // NEW: for admin panel
    public AuthManager  getAuthManager()       { return authManager; }  // NEW: for admin panel
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
