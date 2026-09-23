package sync;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class MutualExclusion {
    private final int nodeId;
    private final String nextPeerAddress;
    private boolean wantsToUpdateScore = false;
    private boolean hasToken = false;
    private String scoreData = "{}";
    private final String internalSecret;
    private final HttpClient client = HttpClient.newHttpClient();

    public MutualExclusion(int nodeId, String nextPeerAddress, boolean startsWithToken, String internalSecret) {
        this.nodeId = nodeId;
        this.nextPeerAddress = nextPeerAddress;
        this.hasToken = startsWithToken;
        this.internalSecret = internalSecret;
    }

    public synchronized void requestCriticalSection() {
        this.wantsToUpdateScore = true;
        if (hasToken) {
            executeCriticalSectionAndPass();
        }
    }

    public synchronized void receiveToken(String incomingScores) {
        this.hasToken = true;
        if (incomingScores != null && !incomingScores.isEmpty()) {
            this.scoreData = incomingScores;
        }

        if (wantsToUpdateScore) {
            executeCriticalSectionAndPass();
        } else {
            passToken();
        }
    }

    private void executeCriticalSectionAndPass() {
        // Critical Section: Update Shared Scoreboard
        System.out.println("Node " + nodeId + " entered CRITICAL SECTION. Updating score...");
        this.scoreData = "{\"last_updated_by\":" + nodeId + ",\"timestamp\":" + System.currentTimeMillis() + "}";
        this.wantsToUpdateScore = false;
        
        passToken();
    }

    private void passToken() {
        if (!hasToken) return;
        this.hasToken = false;

        new Thread(() -> {
            try {
                String payload = String.format("{\"token_holder\":%d,\"scores\":%s}", nodeId, scoreData);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + nextPeerAddress + "/api/token"))
                        .header("Content-Type", "application/json")
                        .header("X-Internal-Secret", internalSecret)
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .timeout(Duration.ofSeconds(2))
                        .build();

                client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                // If next peer is unreachable, retry or hold token temporarily
                System.err.println("Node " + nodeId + " failed to pass token to " + nextPeerAddress);
                synchronized (MutualExclusion.this) {
                    this.hasToken = true;
                }
            }
        }).start();
    }

    public synchronized boolean hasToken() {
        return hasToken;
    }

    public synchronized boolean isWaiting() {
        return wantsToUpdateScore;
    }

    public synchronized String getScoreData() {
        return scoreData;
    }
}