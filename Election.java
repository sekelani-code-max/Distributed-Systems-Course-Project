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
    private final List<String> peerAddresses;
    private int currentLeaderId;
    private boolean isElectionInProgress = false;
    private final String internalSecret;
    private final HttpClient client = HttpClient.newHttpClient();

    public Election(int nodeId, List<String> peerAddresses, String internalSecret) {
        this.nodeId = nodeId;
        this.peerAddresses = peerAddresses;
        this.internalSecret = internalSecret;
        this.currentLeaderId = peerAddresses.size() - 1; // Highest ID is initial host
    }

    public synchronized void startElection() {
        if (isElectionInProgress) return;
        System.out.println("Node " + nodeId + " starting election...");
        isElectionInProgress = true;

        new Thread(() -> {
            AtomicBoolean receivedOk = new AtomicBoolean(false);
            CompletableFuture<?>[] futures = java.util.stream.IntStream.range(0, peerAddresses.size())
                .filter(peerId -> peerId > nodeId)
                .mapToObj(peerId -> sendElectionMessage(peerId, "ELECTION")
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
        for (int peerId = 0; peerId < peerAddresses.size(); peerId++) {
            if (peerId != nodeId) {
                sendElectionMessage(peerId, "COORDINATOR");
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

    private CompletableFuture<HttpResponse<String>> sendElectionMessage(int peerId, String type) {
        String payload = String.format("{\"type\":\"%s\",\"sender_id\":%d}", type, nodeId);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + peerAddresses.get(peerId) + "/api/election"))
                .header("Content-Type", "application/json")
                .header("X-Internal-Secret", internalSecret)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(1))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .exceptionally(ex -> null);
    }

    public int getCurrentLeaderId() { return currentLeaderId; }
}