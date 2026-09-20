package api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import models.Clock;
import models.Message;
import sync.Election;
import sync.MutualExclusion;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChatHandler implements HttpHandler {
    private final Clock clock;
    private final MutualExclusion mutex;
    private final Election election;
    private final List<Message> messageLog = Collections.synchronizedList(new ArrayList<>());

    public ChatHandler(Clock clock, MutualExclusion mutex, Election election) {
        this.clock = clock;
        this.mutex = mutex;
        this.election = election;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        if ("POST".equals(method) && "/api/chat".equals(path)) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            
            // Extract attributes using Regular Expressions (no external JSON libraries)
            int senderId = Integer.parseInt(extractJsonValue(body, "sender_id"));
            String text = extractJsonValue(body, "text");
            int lamport = Integer.parseInt(extractJsonValue(body, "lamport"));
            int[] vector = parseJsonArray(extractJsonArray(body, "vector"));

            // Update clock state & save message
            clock.updateOnReceive(lamport, vector);
            Message msg = new Message(senderId, text, lamport, vector);
            synchronized (messageLog) {
                messageLog.add(msg);
                Collections.sort(messageLog);
            }
            System.out.println("Received & Logged Message: " + msg);

            sendResponse(exchange, 200, "{\"status\":\"Message Received\"}");
        } else if ("POST".equals(method) && "/api/token".equals(path)) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String scores = extractJsonValue(body, "scores");
            
            mutex.receiveToken(scores);
            sendResponse(exchange, 200, "{\"status\":\"Token Handled\"}");
        } else if ("POST".equals(method) && "/api/election".equals(path)) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String type = extractJsonValue(body, "type");
            int senderId = Integer.parseInt(extractJsonValue(body, "sender_id"));

            if ("ELECTION".equals(type)) {
                election.handleElectionMessage(senderId);
                sendResponse(exchange, 200, "{\"status\":\"OK\"}");
            } else if ("COORDINATOR".equals(type)) {
                election.handleCoordinatorMessage(senderId);
                sendResponse(exchange, 200, "{\"status\":\"ACK\"}");
            } else {
                sendResponse(exchange, 200, "{\"status\":\"OK\"}");
            }
        } else if ("GET".equals(method) && "/api/health".equals(path)) {
            sendResponse(exchange, 200, "{\"status\":\"ALIVE\"}");
        } else {
            sendResponse(exchange, 404, "Not Found");
        }
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }

    // Helper utilities for manual JSON parsing
    private String extractJsonValue(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\":\\s*\"?([^\",}]+)\"?");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String extractJsonArray(String json, String key) {
        Pattern pattern = Pattern.compile("\"" + key + "\":\\s*\\[([^\\]]*)\\]");
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private int[] parseJsonArray(String arrayStr) {
        if (arrayStr.isEmpty()) return new int[0];
        String[] parts = arrayStr.split(",");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }
}