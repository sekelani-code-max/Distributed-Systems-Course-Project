import com.sun.net.httpserver.HttpServer;
import api.ChatHandler;
import models.Clock;
import persistence.ChatDatabase;
import sync.Election;
import sync.MutualExclusion;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Node {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java Node <nodeId> <port>");
            System.exit(1);
        }

        int nodeId = Integer.parseInt(args[0]);
        int port = Integer.parseInt(args[1]);

        List<String> peerAddresses = Arrays.stream(System.getenv()
                .getOrDefault("CHAT_PEERS", "localhost:8000,localhost:8001,localhost:8002,localhost:8003,localhost:8004,localhost:8005,localhost:8006,localhost:8007,localhost:8008,localhost:8009")
                .split(","))
            .map(String::trim)
            .collect(Collectors.toList());
        if (nodeId < 0 || nodeId >= peerAddresses.size()) {
            throw new IllegalArgumentException("Node ID must be between 0 and " + (peerAddresses.size() - 1));
        }
        int totalNodes = peerAddresses.size();
        String nextPeerAddress = peerAddresses.get((nodeId + 1) % totalNodes);
        String internalSecret = System.getenv().getOrDefault("CHAT_INTERNAL_SECRET", "dev-internal-secret");
        String databasePath = System.getenv().getOrDefault("CHAT_DB_PATH", "chat.db");

        Clock clock = new Clock(nodeId, totalNodes);
        ChatDatabase database = new ChatDatabase(databasePath);
        MutualExclusion mutex = new MutualExclusion(nodeId, nextPeerAddress, nodeId == 0, internalSecret);
        Election election = new Election(nodeId, peerAddresses, internalSecret);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new ChatHandler(clock, mutex, election, database, nodeId, peerAddresses, internalSecret));
        server.setExecutor(Executors.newCachedThreadPool()); // Non-blocking handling
        server.start();

        System.out.println("Node " + nodeId + " running on port " + port);

        // Host Health Monitoring Thread (Periodically checks leader health)
        ScheduledExecutorService healthChecker = Executors.newSingleThreadScheduledExecutor();
        HttpClient client = HttpClient.newHttpClient();

        healthChecker.scheduleAtFixedRate(() -> {
            int leaderId = election.getCurrentLeaderId();
            if (leaderId != nodeId) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://" + peerAddresses.get(leaderId) + "/api/health"))
                            .timeout(Duration.ofSeconds(2))
                            .GET()
                            .build();
                    client.send(req, HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) {
                    System.err.println("Node " + nodeId + ": Host Node " + leaderId + " failed! Initiating Bully Election...");
                    election.startElection();
                }
            }
        }, 5, 5, TimeUnit.SECONDS);

        // Rejoining nodes announce themselves so a recovered higher-priority node can reclaim leadership.
        healthChecker.schedule(election::startElection, 1, TimeUnit.SECONDS);
    }
}