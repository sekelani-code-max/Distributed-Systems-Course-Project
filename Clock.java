package models;

import java.util.Arrays;

public class Clock {
    private int lamportTime = 0;
    private final int[] vectorClock;
    private final int nodeId;

    public Clock(int nodeId, int totalNodes) {
        this.nodeId = nodeId;
        this.vectorClock = new int[totalNodes];
    }

    // Local event tick
    public synchronized void tick() {
        lamportTime++;
        vectorClock[nodeId]++;
    }

    // Update clocks upon receiving a message
    public synchronized void updateOnReceive(int incomingLamport, int[] incomingVector) {
        // 1. Lamport Clock Update: max(local, incoming) + 1
        this.lamportTime = Math.max(this.lamportTime, incomingLamport) + 1;

        // 2. Vector Clock Update: max(local[i], incoming[i]) for all indices
        for (int i = 0; i < vectorClock.length; i++) {
            if (i < incomingVector.length) {
                this.vectorClock[i] = Math.max(this.vectorClock[i], incomingVector[i]);
            }
        }

        // 3. Increment local node position after merge
        this.vectorClock[nodeId]++;
    }

    public synchronized int getLamportTime() { 
        return lamportTime; 
    }

    public synchronized int[] getVectorClock() { 
        return Arrays.copyOf(vectorClock, vectorClock.length); 
    }
}