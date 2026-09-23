package persistence;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class ChatDatabase {
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final long SESSION_DURATION_SECONDS = 86_400;
    private final String url;

    public ChatDatabase(String path) throws SQLException {
        this.url = "jdbc:sqlite:" + path;
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA journal_mode = WAL");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS users (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "username TEXT NOT NULL UNIQUE COLLATE NOCASE, " +
                        "password_hash TEXT NOT NULL, created_at TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS sessions (" +
                        "token_hash TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                        "expires_at INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS conversations (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                        "created_by INTEGER REFERENCES users(id), created_at TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS conversation_members (" +
                        "conversation_id INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, " +
                        "user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE, " +
                        "PRIMARY KEY (conversation_id, user_id))");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS messages (" +
                        "id TEXT PRIMARY KEY, conversation_id INTEGER NOT NULL REFERENCES conversations(id), " +
                        "sender_id INTEGER NOT NULL REFERENCES users(id), sender_username TEXT NOT NULL, " +
                        "text TEXT NOT NULL, lamport INTEGER NOT NULL, vector_json TEXT NOT NULL, " +
                        "created_at TEXT NOT NULL)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS messages_conversation_idx ON messages(conversation_id, created_at)");
                statement.executeUpdate("INSERT OR IGNORE INTO conversations(id, name, created_at) VALUES (1, 'General', datetime('now'))");
            }
        }
    }

    public synchronized User register(String username, String password) throws SQLException {
        String cleanUsername = username == null ? "" : username.trim();
        if (!cleanUsername.matches("[A-Za-z0-9_]{3,32}")) {
            throw new IllegalArgumentException("Username must be 3-32 letters, numbers, or underscores");
        }
        if (password == null || password.length() < 8 || password.length() > 128) {
            throw new IllegalArgumentException("Password must be 8-128 characters");
        }
        String hash = hashPassword(password);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO users(username, password_hash, created_at) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, cleanUsername);
            statement.setString(2, hash);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return new User(keys.getInt(1), cleanUsername);
            }
        } catch (SQLException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("UNIQUE")) {
                throw new IllegalArgumentException("Username is already registered");
            }
            throw exception;
        }
    }

    public synchronized String login(String username, String password) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, username, password_hash FROM users WHERE username = ? COLLATE NOCASE")) {
            statement.setString(1, username == null ? "" : username.trim());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !verifyPassword(password, result.getString("password_hash"))) {
                    throw new SecurityException("Invalid username or password");
                }
                String token = UUID.randomUUID() + "-" + UUID.randomUUID();
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO sessions(token_hash, user_id, expires_at) VALUES (?, ?, ?)")) {
                    insert.setString(1, sha256(token));
                    insert.setInt(2, result.getInt("id"));
                    insert.setLong(3, Instant.now().getEpochSecond() + SESSION_DURATION_SECONDS);
                    insert.executeUpdate();
                }
                return token;
            }
        }
    }

    public synchronized LoginResult loginUser(String username, String password) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, username, password_hash FROM users WHERE username = ? COLLATE NOCASE")) {
            statement.setString(1, username == null ? "" : username.trim());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !verifyPassword(password, result.getString("password_hash"))) {
                    throw new SecurityException("Invalid username or password");
                }
                User user = new User(result.getInt("id"), result.getString("username"));
                return new LoginResult(user, createSession(connection, user));
            }
        }
    }

    public synchronized String createSession(User user) throws SQLException {
        try (Connection connection = open()) {
            return createSession(connection, user);
        }
    }

    private String createSession(Connection connection, User user) throws SQLException {
        String token = UUID.randomUUID() + "-" + UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO sessions(token_hash, user_id, expires_at) VALUES (?, ?, ?)")) {
            insert.setString(1, sha256(token));
            insert.setInt(2, user.id());
            insert.setLong(3, Instant.now().getEpochSecond() + SESSION_DURATION_SECONDS);
            insert.executeUpdate();
        }
        return token;
    }

    public synchronized User authenticate(String token) throws SQLException {
        if (token == null || token.isBlank()) return null;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT u.id, u.username FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND s.expires_at > ?")) {
            statement.setString(1, sha256(token));
            statement.setLong(2, Instant.now().getEpochSecond());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new User(result.getInt("id"), result.getString("username")) : null;
            }
        }
    }

    public synchronized void ensureUser(int userId, String username) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO users(id, username, password_hash, created_at) VALUES (?, ?, ?, ?)")) {
            statement.setInt(1, userId);
            statement.setString(2, username);
            statement.setString(3, "remote-user-no-login");
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    public synchronized void saveMessage(MessageRecord message) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO messages(id, conversation_id, sender_id, sender_username, text, lamport, vector_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, message.id());
            statement.setInt(2, message.conversationId());
            statement.setInt(3, message.senderId());
            statement.setString(4, message.senderUsername());
            statement.setString(5, message.text());
            statement.setInt(6, message.lamport());
            statement.setString(7, message.vectorJson());
            statement.setString(8, message.createdAt());
            statement.executeUpdate();
        }
    }

    public synchronized List<MessageRecord> listMessages(int conversationId, int limit) throws SQLException {
        List<MessageRecord> messages = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, conversation_id, sender_id, sender_username, text, lamport, vector_json, created_at FROM messages WHERE conversation_id = ? ORDER BY created_at DESC, lamport DESC LIMIT ?")) {
            statement.setInt(1, conversationId);
            statement.setInt(2, Math.max(1, Math.min(limit, 200)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    messages.add(new MessageRecord(result.getString("id"), result.getInt("conversation_id"),
                            result.getInt("sender_id"), result.getString("sender_username"), result.getString("text"),
                            result.getInt("lamport"), result.getString("vector_json"), result.getString("created_at")));
                }
            }
        }
        java.util.Collections.reverse(messages);
        return messages;
    }

    public synchronized int createConversation(String name, int userId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO conversations(name, created_by, created_at) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name == null || name.isBlank() ? "Conversation" : name.trim());
            statement.setInt(2, userId);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                int id = keys.getInt(1);
                try (PreparedStatement member = connection.prepareStatement(
                        "INSERT INTO conversation_members(conversation_id, user_id) VALUES (?, ?)")) {
                    member.setInt(1, id);
                    member.setInt(2, userId);
                    member.executeUpdate();
                }
                return id;
            }
        }
    }

    public synchronized List<Conversation> listConversations() throws SQLException {
        List<Conversation> conversations = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, created_at FROM conversations ORDER BY id")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    conversations.add(new Conversation(result.getInt("id"), result.getString("name"), result.getString("created_at")));
                }
            }
        }
        return conversations;
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private static String hashPassword(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(password, salt, HASH_ITERATIONS);
        return HASH_ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
    }

    private static boolean verifyPassword(String password, String stored) {
        try {
            String[] parts = stored.split(":");
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            return MessageDigest.isEqual(expected, pbkdf2(password, salt, Integer.parseInt(parts[0])));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash password", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static final class User {
        private final int id;
        private final String username;

        public User(int id, String username) {
            this.id = id;
            this.username = username;
        }

        public int id() { return id; }
        public String username() { return username; }
    }

    public static final class LoginResult {
        private final User user;
        private final String token;

        public LoginResult(User user, String token) {
            this.user = user;
            this.token = token;
        }

        public User user() { return user; }
        public String token() { return token; }
    }

    public static final class MessageRecord {
        private final String id;
        private final int conversationId;
        private final int senderId;
        private final String senderUsername;
        private final String text;
        private final int lamport;
        private final String vectorJson;
        private final String createdAt;

        public MessageRecord(String id, int conversationId, int senderId, String senderUsername,
                             String text, int lamport, String vectorJson, String createdAt) {
            this.id = id;
            this.conversationId = conversationId;
            this.senderId = senderId;
            this.senderUsername = senderUsername;
            this.text = text;
            this.lamport = lamport;
            this.vectorJson = vectorJson;
            this.createdAt = createdAt;
        }

        public String id() { return id; }
        public int conversationId() { return conversationId; }
        public int senderId() { return senderId; }
        public String senderUsername() { return senderUsername; }
        public String text() { return text; }
        public int lamport() { return lamport; }
        public String vectorJson() { return vectorJson; }
        public String createdAt() { return createdAt; }
    }

    public static final class Conversation {
        private final int id;
        private final String name;
        private final String createdAt;

        public Conversation(int id, String name, String createdAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
        }

        public int id() { return id; }
        public String name() { return name; }
        public String createdAt() { return createdAt; }
    }
}
