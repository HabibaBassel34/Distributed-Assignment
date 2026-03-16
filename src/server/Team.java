package server;

import model.User;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Team {
    private String name;
    private User creator;
    private ClientHandler creatorHandler;
    private List<User> members;
    private List<ClientHandler> memberHandlers;
    private String category;
    private String difficulty;
    private int numQuestions;
    private int maxPlayers;
    private volatile boolean gameStarted = false;
    private volatile boolean cancelled = false;

    // Bug fix: keep a reference to GameServer so waitForGame() can call back
    // to remove the team or trigger a match - without this the team was never
    // cleaned up and matching never actually happened
    private GameServer gameServer;

    public Team(String name, User creator, ClientHandler creatorHandler,
            String category, String difficulty, int numQuestions, int maxPlayers,
            GameServer gameServer) {
        this.name = name;
        this.creator = creator;
        this.creatorHandler = creatorHandler;
        this.category = category;
        this.difficulty = difficulty;
        this.numQuestions = numQuestions;
        this.maxPlayers = maxPlayers;
        this.gameServer = gameServer;
        this.members = new ArrayList<>();
        this.memberHandlers = new ArrayList<>();
        // Creator is first member
        members.add(creator);
        memberHandlers.add(creatorHandler);
    }

    public synchronized boolean addMember(User user, ClientHandler handler) {
        if (members.size() >= maxPlayers) {
            return false;
        }
        members.add(user);
        memberHandlers.add(handler);
        broadcast("[TEAM " + name + "] " + user.getName() + " joined! (" + members.size() + "/" + maxPlayers
                + " players)");
        return true;
    }

    public synchronized void broadcast(String message) {
        for (ClientHandler h : memberHandlers) {
            h.send(message);
        }
    }

    public boolean isFull() {
        return members.size() >= maxPlayers;
    }

    public int getMemberCount() {
        return members.size();
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public String getName() {
        return name;
    }

    public User getCreator() {
        return creator;
    }

    public ClientHandler getCreatorHandler() {
        return creatorHandler;
    }

    public String getCategory() {
        return category;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public int getNumQuestions() {
        return numQuestions;
    }

    public List<User> getMembers() {
        return Collections.unmodifiableList(members);
    }

    public List<ClientHandler> getMemberHandlers() {
        return Collections.unmodifiableList(memberHandlers);
    }

    public boolean isGameStarted() {
        return gameStarted;
    }

    public void setGameStarted(boolean started) {
        this.gameStarted = started;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean c) {
        this.cancelled = c;
    }

    public void waitForGame(ClientHandler leader) {
        while (!gameStarted && !cancelled) {
            try {
                String input = leader.readLine();

                if (input == null || input.equals("-")) {
                    // Bug fix: remove the team from the server when leader cancels
                    // so it doesn't stay in the available teams list forever
                    cancelled = true;
                    broadcast("[TEAM " + name + "] Game cancelled by leader.");
                    gameServer.removeTeam(name);
                    return;
                }

                if (input.equalsIgnoreCase("start")) {
                    leader.send("Start command received. Looking for an opposing team...");
                    gameServer.tryMatchTeam(this);

                    // Critical fix: after calling tryMatchTeam, stop reading from the
                    // socket immediately. If a match was found, gameStarted is now true
                    // and GameSession owns the input stream. If no match yet, we spin-wait
                    // here without calling readLine() so we don't accidentally consume
                    // the player's game answers while waitForGame is still looping.
                    while (!gameStarted && !cancelled) {
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    }
                    return;
                }

            } catch (Exception e) {
                // Bug fix: also clean up on unexpected disconnection
                cancelled = true;
                gameServer.removeTeam(name);
                return;
            }
        }
    }

    public synchronized void removeMember(User user) {
        int idx = members.indexOf(user);
        if (idx >= 0) {
            members.remove(idx);
            memberHandlers.remove(idx);
        }
    }
}
