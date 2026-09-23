package api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import models.Clock;
import persistence.ChatDatabase;
import sync.Election;
import sync.MutualExclusion;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ChatHandler implements HttpHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Clock clock;
    private final MutualExclusion mutex;
    private final Election election;
    private final ChatDatabase database;
    private final int nodeId;
    private final List<String> peerAddresses;
    private final String internalSecret;
    private final HttpClient client = HttpClient.newHttpClient();

    public ChatHandler(Clock clock, MutualExclusion mutex, Election election, ChatDatabase database,
                       int nodeId, List<String> peerAddresses, String internalSecret) {
        this.clock = clock;
        this.mutex = mutex;
        this.election = election;
        this.database = database;
        this.nodeId = nodeId;
        this.peerAddresses = peerAddresses;
        this.internalSecret = internalSecret;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if ("GET".equals(method) && "/".equals(path)) {
                sendResponse(exchange, 200, ChatPage.html(), "text/html; charset=utf-8");
            } else if ("POST".equals(method) && "/api/auth/register".equals(path)) {
                handleRegister(exchange);
            } else if ("POST".equals(method) && "/api/auth/login".equals(path)) {
                handleLogin(exchange);
            } else if ("GET".equals(method) && "/api/health".equals(path)) {
                sendJson(exchange, 200, object("status", "ALIVE"));
            } else if ("POST".equals(method) && "/api/chat/send".equals(path)) {
                handleSend(exchange);
            } else if ("GET".equals(method) && "/api/chat/messages".equals(path)) {
                handleMessages(exchange);
            } else if ("POST".equals(method) && "/api/conversations".equals(path)) {
                handleCreateConversation(exchange);
            } else if ("GET".equals(method) && "/api/conversations".equals(path)) {
                handleListConversations(exchange);
            } else if ("POST".equals(method) && "/api/chat/internal".equals(path)) {
                handleInternalMessage(exchange);
            } else if ("POST".equals(method) && "/api/token".equals(path)) {
                requireInternal(exchange);
                JsonNode json = JSON.readTree(readBody(exchange));
                mutex.receiveToken(json.path("scores").toString());
                sendJson(exchange, 200, object("status", "Token Handled"));
            } else if ("POST".equals(method) && "/api/election".equals(path)) {
                requireInternal(exchange);
                handleElection(exchange);
            } else if ("POST".equals(method) && "/api/mutex/request".equals(path)) {
                requireUser(exchange);
                mutex.requestCriticalSection();
                sendJson(exchange, 202, object("message", "Mutual-exclusion request queued"));
            } else if ("GET".equals(method) && "/api/mutex/status".equals(path)) {
                requireUser(exchange);
                ObjectNode response = JSON.createObjectNode();
                response.put("has_token", mutex.hasToken());
                response.put("waiting", mutex.isWaiting());
                response.put("score_data", mutex.getScoreData());
                sendJson(exchange, 200, response);
            } else {
                sendResponse(exchange, 404, "Not Found", "text/plain; charset=utf-8");
            }
        } catch (SecurityException exception) {
            sendJson(exchange, 401, object("error", exception.getMessage()));
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, object("error", exception.getMessage()));
        } catch (Exception exception) {
            exception.printStackTrace();
            sendJson(exchange, 500, object("error", "Server error"));
        }
    }

    private void handleRegister(HttpExchange exchange) throws Exception {
        JsonNode json = JSON.readTree(readBody(exchange));
        ChatDatabase.User user = database.register(json.path("username").asText(), json.path("password").asText());
        String token = database.createSession(user);
        ObjectNode response = JSON.createObjectNode();
        response.put("token", token);
        response.put("user_id", user.id());
        response.put("username", user.username());
        sendJson(exchange, 201, response);
    }

    private void handleLogin(HttpExchange exchange) throws Exception {
        JsonNode json = JSON.readTree(readBody(exchange));
        ChatDatabase.LoginResult login = database.loginUser(
            json.path("username").asText(), json.path("password").asText());
        ChatDatabase.User user = login.user();
        ObjectNode response = JSON.createObjectNode();
        response.put("token", login.token());
        response.put("user_id", user.id());
        response.put("username", user.username());
        sendJson(exchange, 200, response);
    }

    private void handleSend(HttpExchange exchange) throws Exception {
        ChatDatabase.User user = requireUser(exchange);
        JsonNode json = JSON.readTree(readBody(exchange));
        String text = json.path("text").asText().trim();
        int conversationId = json.path("conversation_id").asInt(1);
        if (text.isEmpty() || text.length() > 2000) throw new IllegalArgumentException("Message must contain 1-2000 characters");

        clock.tick();
        ChatDatabase.MessageRecord message = new ChatDatabase.MessageRecord(
                UUID.randomUUID().toString(), conversationId, user.id(), user.username(), text,
                clock.getLamportTime(), JSON.writeValueAsString(clock.getVectorClock()), Instant.now().toString());
        database.saveMessage(message);
        broadcast(message);
        sendJson(exchange, 201, messageJson(message));
    }

    private void handleMessages(HttpExchange exchange) throws Exception {
        requireUser(exchange);
        int conversationId = queryInt(exchange, "conversation_id", 1);
        int limit = queryInt(exchange, "limit", 100);
        ArrayNode messages = JSON.createArrayNode();
        for (ChatDatabase.MessageRecord message : database.listMessages(conversationId, limit)) {
            messages.add(messageJson(message));
        }
        ObjectNode response = JSON.createObjectNode();
        response.set("messages", messages);
        sendJson(exchange, 200, response);
    }

    private void handleCreateConversation(HttpExchange exchange) throws Exception {
        ChatDatabase.User user = requireUser(exchange);
        JsonNode json = JSON.readTree(readBody(exchange));
        int id = database.createConversation(json.path("name").asText(), user.id());
        ObjectNode response = JSON.createObjectNode();
        response.put("id", id);
        response.put("name", json.path("name").asText("Conversation"));
        sendJson(exchange, 201, response);
    }

    private void handleListConversations(HttpExchange exchange) throws Exception {
        requireUser(exchange);
        ArrayNode conversations = JSON.createArrayNode();
        for (ChatDatabase.Conversation conversation : database.listConversations()) {
            ObjectNode item = JSON.createObjectNode();
            item.put("id", conversation.id());
            item.put("name", conversation.name());
            item.put("created_at", conversation.createdAt());
            conversations.add(item);
        }
        ObjectNode response = JSON.createObjectNode();
        response.set("conversations", conversations);
        sendJson(exchange, 200, response);
    }

    private void handleInternalMessage(HttpExchange exchange) throws Exception {
        requireInternal(exchange);
        JsonNode json = JSON.readTree(readBody(exchange));
        int[] vector = JSON.convertValue(json.path("vector"), int[].class);
        clock.updateOnReceive(json.path("lamport").asInt(), vector);
        ChatDatabase.MessageRecord message = new ChatDatabase.MessageRecord(
                json.path("id").asText(), json.path("conversation_id").asInt(1), json.path("sender_id").asInt(),
                json.path("sender_username").asText(), json.path("text").asText(), json.path("lamport").asInt(),
                JSON.writeValueAsString(vector), json.path("created_at").asText());
        database.ensureUser(message.senderId(), message.senderUsername());
        database.saveMessage(message);
        sendJson(exchange, 200, object("status", "Message stored"));
    }

    private void handleElection(HttpExchange exchange) throws Exception {
        JsonNode json = JSON.readTree(readBody(exchange));
        String type = json.path("type").asText();
        int senderId = json.path("sender_id").asInt();
        if ("ELECTION".equals(type)) election.handleElectionMessage(senderId);
        else if ("COORDINATOR".equals(type)) election.handleCoordinatorMessage(senderId);
        sendJson(exchange, 200, object("status", "OK"));
    }

    private void broadcast(ChatDatabase.MessageRecord message) throws IOException {
        String payload = JSON.writeValueAsString(messageJson(message));
        for (int peerId = 0; peerId < peerAddresses.size(); peerId++) {
            if (peerId == nodeId) continue;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + peerAddresses.get(peerId) + "/api/chat/internal"))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Secret", internalSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(2)).build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).exceptionally(error -> null);
        }
    }

    private ChatDatabase.User requireUser(HttpExchange exchange) {
        try {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
            ChatDatabase.User user = database.authenticate(token);
            if (user == null) throw new SecurityException("Authentication required");
            return user;
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Authentication unavailable", exception);
        }
    }

    private void requireInternal(HttpExchange exchange) {
        if (!internalSecret.equals(exchange.getRequestHeaders().getFirst("X-Internal-Secret"))) {
            throw new SecurityException("Internal node authentication required");
        }
    }

    private JsonNode messageJson(ChatDatabase.MessageRecord message) throws IOException {
        ObjectNode json = JSON.createObjectNode();
        json.put("id", message.id());
        json.put("conversation_id", message.conversationId());
        json.put("sender_id", message.senderId());
        json.put("sender_username", message.senderUsername());
        json.put("text", message.text());
        json.put("lamport", message.lamport());
        json.set("vector", JSON.readTree(message.vectorJson()));
        json.put("created_at", message.createdAt());
        return json;
    }

    private int queryInt(HttpExchange exchange, String key, int fallback) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return fallback;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(URLDecoder.decode(pair[0], StandardCharsets.UTF_8))) {
                try { return Integer.parseInt(pair[1]); } catch (NumberFormatException ignored) { return fallback; }
            }
        }
        return fallback;
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private ObjectNode object(String key, String value) {
        ObjectNode node = JSON.createObjectNode();
        node.put(key, value);
        return node;
    }

    private void sendJson(HttpExchange exchange, int statusCode, JsonNode response) throws IOException {
        sendResponse(exchange, statusCode, JSON.writeValueAsString(response), "application/json; charset=utf-8");
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response, String contentType) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
