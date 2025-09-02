/*
 * ChatApp.java — minimal multi-user chat using Java sockets + threads
 *
 * How to run (Terminal 1 — server):
 *   javac ChatApp.java
 *   java ChatApp server 5000
 *
 * How to run (Terminal 2..N — clients):
 *   java ChatApp client 127.0.0.1 5000 <yourName>
 *
 * Commands (client):
 *   Type messages and press Enter to send to everyone.
 *   /quit    — leave the chat.
 *
 * Notes:
 * - One file contains both server and client so it compiles easily anywhere.
 * - Server broadcasts messages to all connected clients.
 * - Each client is handled on its own thread.
 */

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ChatApp {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage:\n  Server: java ChatApp server <port>\n  Client: java ChatApp client <host> <port> <name>");
            return;
        }
        switch (args[0].toLowerCase()) {
            case "server":
                int port = (args.length >= 2) ? Integer.parseInt(args[1]) : 5000;
                new Server(port).start();
                break;
            case "client":
                if (args.length < 4) {
                    System.out.println("Client usage: java ChatApp client <host> <port> <name>");
                    return;
                }
                String host = args[1];
                int cport = Integer.parseInt(args[2]);
                String name = args[3];
                new Client(host, cport, name).start();
                break;
            default:
                System.out.println("Unknown mode: " + args[0]);
        }
    }

    /* ===================== SERVER ===================== */
    static class Server {
        private final int port;
        private ServerSocket serverSocket;
        // Thread-safe set of clients
        private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();

        Server(int port) {
            this.port = port;
        }

        void start() {
            try (ServerSocket ss = new ServerSocket(port)) {
                this.serverSocket = ss;
                System.out.println("[Server] Listening on port " + port + " …");
                while (true) {
                    Socket socket = ss.accept();
                    socket.setTcpNoDelay(true);
                    ClientHandler handler = new ClientHandler(socket);
                    clients.add(handler);
                    handler.start();
                }
            } catch (IOException e) {
                System.err.println("[Server] Fatal: " + e.getMessage());
            }
        }

        void broadcast(String msg, ClientHandler from) {
            // Send to all clients except the sender (standard room-style)
            for (ClientHandler ch : clients) {
                if (ch != from) {
                    ch.send(msg);
                }
            }
        }

        class ClientHandler extends Thread {
            private final Socket socket;
            private BufferedReader in;
            private PrintWriter out;
            private String name = "Anonymous";

            ClientHandler(Socket socket) { this.socket = socket; }

            @Override public void run() {
                String remote = socket.getRemoteSocketAddress().toString();
                try {
                    in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

                    // First line from client is the chosen display name
                    String intro = in.readLine();
                    if (intro != null && intro.startsWith("/name ")) {
                        name = intro.substring(6).trim();
                    }

                    System.out.println("[Server] " + name + " connected from " + remote);
                    broadcast("🟢 " + name + " joined the chat", this);
                    out.println("Welcome, " + name + "! Type /quit to leave.");

                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.equalsIgnoreCase("/quit")) break;
                        if (line.startsWith("/name ")) { // allow rename
                            String old = name;
                            name = line.substring(6).trim();
                            broadcast("✏️  " + old + " is now known as " + name, this);
                            continue;
                        }
                        String msg = "" + name + ": " + line;
                        System.out.println("[Room] " + msg);
                        broadcast(msg, this);
                    }
                } catch (IOException e) {
                    System.err.println("[Server] Connection problem with " + remote + ": " + e.getMessage());
                } finally {
                    try { socket.close(); } catch (IOException ignored) {}
                    clients.remove(this);
                    System.out.println("[Server] " + name + " disconnected");
                    broadcast("🔴 " + name + " left the chat", this);
                }
            }

            void send(String msg) { if (out != null) out.println(msg); }
        }
    }

    /* ===================== CLIENT ===================== */
    static class Client {
        private final String host; private final int port; private final String name;
        Client(String host, int port, String name) { this.host = host; this.port = port; this.name = name; }

        void start() {
            try (Socket socket = new Socket(host, port)) {
                socket.setTcpNoDelay(true);
                System.out.println("[Client] Connected to " + host + ":" + port);

                BufferedReader serverIn = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                PrintWriter serverOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

                // Introduce ourselves
                serverOut.println("/name " + name);

                // Thread to read messages from server
                Thread reader = new Thread(() -> {
                    String line;
                    try {
                        while ((line = serverIn.readLine()) != null) {
                            System.out.println(line);
                        }
                    } catch (IOException e) {
                        System.out.println("[Client] Disconnected: " + e.getMessage());
                    }
                }, "server-listener");
                reader.setDaemon(true);
                reader.start();

                // Main loop: read stdin and send to server
                BufferedReader console = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
                String input;
                System.out.println("Type messages. Use /quit to exit, /name <new> to rename.");
                while ((input = console.readLine()) != null) {
                    serverOut.println(input);
                    if ("/quit".equalsIgnoreCase(input.trim())) break;
                }
            } catch (IOException e) {
                System.err.println("[Client] Error: " + e.getMessage());
            }
        }
    }
}
