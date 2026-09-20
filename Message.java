package models;

import java.util.Arrays;

public class Message implements Comparable<Message> {
    private final int senderId;
    private final String text;
    private final int lamportTime;
    private final int[] vectorClock;

    public Message(int senderId, String text, int lamportTime, int[] vectorClock) {
        this.senderId = senderId;
        this.text = text;
        this.lamportTime = lamportTime;
        this.vectorClock = Arrays.copyOf(vectorClock, vectorClock.length);
    }

    public int getSenderId() { return senderId; }
    public String getText() { return text; }
    public int getLamportTime() { return lamportTime; }
    public int[] getVectorClock() { return vectorClock; }

    @Override
    public int compareTo(Message other) {
        // Causal/Total Ordering: Compare Lamport times first, break ties using senderId
        if (this.lamportTime != other.lamportTime) {
            return Integer.compare(this.lamportTime, other.lamportTime);
        }
        return Integer.compare(this.senderId, other.senderId);
    }

    @Override
    public String toString() {
        return String.format("[L:%d | V:%s] Node %d: %s", 
            lamportTime, Arrays.toString(vectorClock), senderId, text);
    }
}