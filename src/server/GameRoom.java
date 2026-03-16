package server;

import model.User;

import java.util.*;


public class GameRoom {

    private String roomId;
    private int    minPlayers;
    private int    maxPlayers;

    private List<User>          members        = new ArrayList<>();
    private List<ClientHandler> memberHandlers = new ArrayList<>();

    private volatile boolean gameStarted = false;

    public GameRoom(String roomId, int minPlayers, int maxPlayers) {
        this.roomId     = roomId;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
    }

    public synchronized boolean addMember(User user, ClientHandler handler) {
        if (gameStarted || members.size() >= maxPlayers) return false;
        members.add(user);
        memberHandlers.add(handler);
        broadcast("[" + roomId + "] " + user.getName() + " joined! ("
                + members.size() + "/" + maxPlayers + " players)");
        return true;
    }

    public synchronized void removeMember(User user) {
        int idx = members.indexOf(user);
        if (idx >= 0) {
            members.remove(idx);
            memberHandlers.remove(idx);
        }
    }

    public synchronized void broadcast(String message) {
        for (ClientHandler h : memberHandlers) {
            h.send(message);
        }
    }


    public String getRoomId()                   { return roomId; }
    public int    getMemberCount()              { return members.size(); }
    public int    getMinPlayers()               { return minPlayers; }
    public int    getMaxPlayers()               { return maxPlayers; }
    public boolean isFull()                     { return members.size() >= maxPlayers; }
    public boolean hasMinPlayers()              { return members.size() >= minPlayers; }
    public boolean isGameStarted()              { return gameStarted; }
    public void    setGameStarted(boolean v)    { this.gameStarted = v; }

    public List<User>          getMembers()        { return Collections.unmodifiableList(members); }
    public List<ClientHandler> getMemberHandlers() { return Collections.unmodifiableList(memberHandlers); }
}
