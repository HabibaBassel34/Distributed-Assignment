package client;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class TriviaClient implements Runnable {
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 5555;

    private Socket socket;
    private BufferedReader networkInput;
    private PrintWriter networkOutput;
    private Scanner userEntry;

    public TriviaClient(Socket socket) throws IOException {
        this.socket = socket;
        this.networkInput = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.networkOutput = new PrintWriter(socket.getOutputStream(), true);
        this.userEntry = new Scanner(System.in);
    }

    @Override
    public void run() {
        try {
            String line;
            while ((line = networkInput.readLine()) != null) {
                System.out.println(line);
            }
        } catch (IOException e) {
            System.out.println("\n[Disconnected from server]");
        }
    }

    public void talkToServer() {
        String input;
        while (userEntry.hasNextLine()) {
            input = userEntry.nextLine();
            networkOutput.println(input);
            if (input.trim().equals("-")) {
                System.out.println("Quitting...");
                break;
            }
        }
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        System.out.println("Connecting to Trivia Server at " + host + ":" + port + "...");

        try {
            Socket socket = new Socket(host, port);
            System.out.println("Connected!\n");

            TriviaClient client = new TriviaClient(socket);

            // server-listener thread
            Thread listenerThread = new Thread(client);
            listenerThread.setDaemon(true);
            listenerThread.start();

            // handles user input
            client.talkToServer();

            socket.close();
        } catch (IOException e) {
            System.out.println("Could not connect to server: " + e.getMessage());
        }
    }
}