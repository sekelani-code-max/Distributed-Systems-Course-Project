package sync;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class Election {
    private final int nodeId;
    private final List<Integer> peerPorts;
    private int currentLeaderId;
    private boolean isElectionInProgress = false;
    private final HttpClient client = HttpClient.newHttpClient();

    public Election(int nodeId, List<Integer> peerPorts) {
        this.nodeId = nodeId;
        this.peerPorts = peerPorts;
        this.currentLeaderId = peerPorts.size() - 1; // Highest ID is initial host
    }

    public synchronized void startElection() {
        if (isElectionInProgress) return;
        System.out.println("Node " + nodeId + " starting election...");
        isElectionInProgress = true;

        new Thread(() -> {
            AtomicBoolean receivedOk = new AtomicBoolean(false);
            CompletableFuture<?>[] futures = peerPorts.stream()
                .filter(port -> getPeerIdFromPort(port) > nodeId)
                .map(port -> sendElectionMessage(port, "ELECTION")
                    .thenAccept(res -> {
                        if (res != null && res.statusCode() == 200) {
                            receivedOk.set(true);
                        }
                    }))
                .toArray(CompletableFuture[]::new);

            try {
                CompletableFuture.allOf(futures).get();
            } catch (Exception ignored) {}

            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

            if (!receivedOk.get()) {
                // No higher node responded -> Declare self as Leader
                declareSelfAsLeader();
            }
        }).start();
    }

    private void declareSelfAsLeader() {
        this.currentLeaderId = nodeId;
        this.isElectionInProgress = false;
        System.out.println("Node " + nodeId + " is now the COORDINATOR!");

        // Broadcast COORDINATOR message to all peers
        for (int port : peerPorts) {
            if (getPeerIdFromPort(port) != nodeId) {
                sendElectionMessage(port, "COORDINATOR");
            }
        }
    }

    public void handleElectionMessage(int senderId) {
        // Send OK response handled in HTTP Handler, start local election if sender ID is lower
        if (senderId < nodeId && !isElectionInProgress) {
            startElection();
        }
    }

    public synchronized void handleCoordinatorMessage(int newLeaderId) {
        this.currentLeaderId = newLeaderId;
        this.isElectionInProgress = false;
        System.out.println("New Leader recognized: Node " + newLeaderId);
    }

    private CompletableFuture<HttpResponse<String>> sendElectionMessage(int port, String type) {
        String payload = String.format("{\"type\":\"%s\",\"sender_id\":%d}", type, nodeId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/election"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(1))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .exceptionally(ex -> null);
    }

    private int getPeerIdFromPort(int port) {
        return peerPorts.indexOf(port);
    }

    public int getCurrentLeaderId() { return currentLeaderId; }
}