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

    public Team(String name, User creator, ClientHandler creatorHandler,
            String category, String difficulty, int numQuestions, int maxPlayers) {
        this.name = name;
        this.creator = creator;
        this.creatorHandler = creatorHandler;
        this.category = category;
        this.difficulty = difficulty;
        this.numQuestions = numQuestions;
        this.maxPlayers = maxPlayers;

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
                    cancelled = true;
                    broadcast("[TEAM " + name + "] Game cancelled by leader.");
                    return;
                }
                if (input.equalsIgnoreCase("start")) {
                    leader.send("Start command received. Waiting for server to match teams...");
                    // The game server will check team sizes and start when matched
                }
            } catch (Exception e) {
                cancelled = true;
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