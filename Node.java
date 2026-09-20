import com.sun.net.httpserver.HttpServer;
import api.ChatHandler;
import models.Clock;
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

        List<Integer> peerPorts = Arrays.asList(8000, 8001, 8002, 8003, 8004, 8005, 8006, 8007, 8008, 8009);
        int totalNodes = peerPorts.size();
        int nextPeerPort = peerPorts.get((nodeId + 1) % totalNodes);

        Clock clock = new Clock(nodeId, totalNodes);
        MutualExclusion mutex = new MutualExclusion(nodeId, nextPeerPort, nodeId == 0);
        Election election = new Election(nodeId, peerPorts);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", new ChatHandler(clock, mutex, election));
        server.setExecutor(Executors.newCachedThreadPool()); // Non-blocking handling
        server.start();

        System.out.println("Node " + nodeId + " running on port " + port);

        // Host Health Monitoring Thread (Periodically checks leader health)
        ScheduledExecutorService healthChecker = Executors.newSingleThreadScheduledExecutor();
        HttpClient client = HttpClient.newHttpClient();

        healthChecker.scheduleAtFixedRate(() -> {
            int leaderId = election.getCurrentLeaderId();
            if (leaderId != nodeId) {
                int leaderPort = peerPorts.get(leaderId);
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:" + leaderPort + "/api/health"))
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
    }
}