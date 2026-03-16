package server;

import model.User;

import java.util.*;

/**
 * GameRoom - Additional Feature 2 (Public Game Room)
 *
 * A public room that any logged-in player can join without knowing other players.
 * Rooms are created at runtime (not from a file) and deleted when the server shuts down.
 *
 * How it works:
 *   1. Player picks "Join Public Game Room" from the multiplayer menu.
 *   2. GameServer finds an available room (not full, not started) or creates a new one.
 *   3. Player waits inside the room until enough players have joined.
 *   4. Game starts automatically when the room reaches max capacity,
 *      OR when a player types "start" and min players are present.
 *
 * ASSUMPTION: Room settings (category, difficulty, question count) are fixed
 * to "random" mode because players don't know each other and can't agree on settings.
 * Questions will be random (all categories and difficulties mixed).
 *
 * ASSUMPTION: min and max player counts come from config.txt keys:
 *   min_players_per_room and max_players_per_room.
 *
 * ASSUMPTION: Room IDs are auto-generated strings like "Room-1", "Room-2", etc.
 */
public class GameRoom {

    private String roomId;    // e.g. "Room-1"
    private int    minPlayers;
    private int    maxPlayers;

    // Parallel lists: members.get(i) matches memberHandlers.get(i)
    private List<User>          members        = new ArrayList<>();
    private List<ClientHandler> memberHandlers = new ArrayList<>();

    // True once the game has been launched - no new players allowed after this
    private volatile boolean gameStarted = false;

    public GameRoom(String roomId, int minPlayers, int maxPlayers) {
        this.roomId     = roomId;
        this.minPlayers = minPlayers;
        this.maxPlayers = maxPlayers;
    }

    // Add a player to the room. Returns false if the room is full or game already started.
    public synchronized boolean addMember(User user, ClientHandler handler) {
        if (gameStarted || members.size() >= maxPlayers) return false;
        members.add(user);
        memberHandlers.add(handler);
        // Tell everyone in the room who just joined
        broadcast("[" + roomId + "] " + user.getName() + " joined! ("
                + members.size() + "/" + maxPlayers + " players)");
        return true;
    }

    // Remove a player (e.g., they disconnected while waiting)
    public synchronized void removeMember(User user) {
        int idx = members.indexOf(user);
        if (idx >= 0) {
            members.remove(idx);
            memberHandlers.remove(idx);
        }
    }

    // Send a message to every player currently in this room
    public synchronized void broadcast(String message) {
        for (ClientHandler h : memberHandlers) {
            h.send(message);
        }
    }

    // Getters

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
