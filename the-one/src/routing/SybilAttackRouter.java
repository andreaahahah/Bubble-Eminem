package routing;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

/**
 * Malicious router that implements a Sybil attack and a Buffer Overflow attack
 * by exploiting the Bubble Rap routing protocol.
 */
public class SybilAttackRouter extends BubbleRapRouter {
    public static final String SYBIL_NS = "SybilAttackRouter";
    
    public static final String NROF_FAKE_IDS = "nrofFakeIds";
    public static final String GARBAGE_INTERVAL = "garbageInterval";
    public static final String GARBAGE_SIZE = "garbageSize";
    public static final String TARGET_HIGH_CENTRALITY = "targetHighCentrality";

    private int nrofFakeIds;
    private int garbageInterval;
    private int garbageSize;
    private boolean targetHighCentrality;

    private double lastGarbageTime;
    private int garbageCounter;

    public SybilAttackRouter(Settings s) {
        super(s);
        Settings sybilSettings = new Settings(SYBIL_NS);
        
        nrofFakeIds = sybilSettings.contains(NROF_FAKE_IDS) ? sybilSettings.getInt(NROF_FAKE_IDS) : 10;
        garbageInterval = sybilSettings.contains(GARBAGE_INTERVAL) ? sybilSettings.getInt(GARBAGE_INTERVAL) : 5;
        garbageSize = sybilSettings.contains(GARBAGE_SIZE) ? sybilSettings.getInt(GARBAGE_SIZE) : 500000;
        targetHighCentrality = sybilSettings.contains(TARGET_HIGH_CENTRALITY) ? sybilSettings.getBoolean(TARGET_HIGH_CENTRALITY) : true;
        
        lastGarbageTime = 0;
        garbageCounter = 0;
    }

    protected SybilAttackRouter(SybilAttackRouter r) {
        super(r);
        this.nrofFakeIds = r.nrofFakeIds;
        this.garbageInterval = r.garbageInterval;
        this.garbageSize = r.garbageSize;
        this.targetHighCentrality = r.targetHighCentrality;
        
        this.lastGarbageTime = r.lastGarbageTime;
        this.garbageCounter = r.garbageCounter;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        
        if (con.isUp()) {
            DTNHost otherHost = con.getOtherNode(getHost());
            
            if (otherHost.getRouter() instanceof BubbleRapRouter) {
                BubbleRapRouter otherRouter = (BubbleRapRouter) otherHost.getRouter();
                
                try {
                    // 1. SYBIL ATTACK: Boost this node's own localRank
                    // We bypass private visibility using reflection to access internal BubbleRap maps
                    Field localRankField = BubbleRapRouter.class.getDeclaredField("localRank");
                    localRankField.setAccessible(true);
                    int currentLocalRank = localRankField.getInt(this);
                    localRankField.setInt(this, currentLocalRank + nrofFakeIds);
                    
                    // 2. INJECT FAKE ENCOUNTER DATA into the other node's router
                    // This artificially boosts this node's own encounter count and familiarity on the target node
                    Field otherEcField = BubbleRapRouter.class.getDeclaredField("encounterCount");
                    otherEcField.setAccessible(true);
                    Map<DTNHost, Integer> otherEncounterCount = (Map<DTNHost, Integer>) otherEcField.get(otherRouter);
                    
                    Field otherFsField = BubbleRapRouter.class.getDeclaredField("familiarSet");
                    otherFsField.setAccessible(true);
                    Set<DTNHost> otherFamiliarSet = (Set<DTNHost>) otherFsField.get(otherRouter);
                    
                    // Add fabricated encounters for our host on the other node
                    int currentCount = otherEncounterCount.getOrDefault(getHost(), 0);
                    otherEncounterCount.put(getHost(), currentCount + nrofFakeIds);
                    
                    // Ensure we are marked as highly central/familiar to the other node
                    otherFamiliarSet.add(getHost());
                    
                } catch (Exception e) {
                    System.err.println("SybilAttackRouter reflection injection failed: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void update() {
        super.update();
        
        double currentTime = SimClock.getTime();
        
        // BUFFER OVERFLOW ATTACK: Generate garbage messages
        if (currentTime - lastGarbageTime >= garbageInterval) {
            DTNHost targetHost = null;
            
            if (targetHighCentrality) {
                int maxRank = -1;
                for (Connection c : getConnections()) {
                    DTNHost other = c.getOtherNode(getHost());
                    if (other.getRouter() instanceof BubbleRapRouter) {
                        int rank = ((BubbleRapRouter) other.getRouter()).getLocalRank();
                        if (rank > maxRank) {
                            maxRank = rank;
                            targetHost = other;
                        }
                    }
                }
            }
            
            // If targeting high centrality is disabled, or no BubbleRap neighbors are found, pick a random available connection
            if (targetHost == null && !getConnections().isEmpty()) {
                targetHost = getConnections().get(0).getOtherNode(getHost());
            }
            
            if (targetHost != null) {
                String msgId = "GARB_" + getHost().getAddress() + "_" + garbageCounter;
                Message garbageMsg = new Message(getHost(), targetHost, msgId, garbageSize);
                
                // createNewMessage registers the message in the simulator and places it in this node's buffer
                createNewMessage(garbageMsg);
                
                garbageCounter++;
                lastGarbageTime = currentTime;
            }
        }
    }

    @Override
    public int getLocalRank() {
        // Guarantee that this malicious node appears more central than it really is
        return super.getLocalRank() + nrofFakeIds;
    }

    @Override
    public SybilAttackRouter replicate() {
        return new SybilAttackRouter(this);
    }
}
