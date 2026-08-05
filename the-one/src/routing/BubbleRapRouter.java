package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import routing.util.RoutingInfo;
import util.Tuple;
import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

/**
 * Bubble Rap router for The ONE Simulator.
 */
public class BubbleRapRouter extends ActiveRouter {
    public static final String BUBBLERAP_NS = "BubbleRapRouter";
    
    public static final String FAMILIAR_THRESHOLD_S = "familiarThreshold";
    public static final String CENTRALITY_WINDOW_S = "centralityWindow";
    public static final String REPUTATION_ENABLED_S = "reputationEnabled";
    public static final String GREY_THRESHOLD_S = "greyThreshold";
    public static final String BLACK_THRESHOLD_S = "blackThreshold";
    
    private int familiarThreshold;
    private int centralityWindow;
    private boolean reputationEnabled;
    private double greyThreshold;
    private double blackThreshold;
    
    private Map<DTNHost, Integer> encounterCount;
    private Set<DTNHost> familiarSet;
    private Map<DTNHost, List<Double>> encounterTimes;
    private int localRank;
    private Map<DTNHost, Double> reputationScores;
    
    public int garbageDetected;
    public int messagesFromBlacklisted;

    public BubbleRapRouter(Settings s) {
        super(s);
        Settings brSettings = new Settings(BUBBLERAP_NS);
        
        familiarThreshold = brSettings.contains(FAMILIAR_THRESHOLD_S) ? brSettings.getInt(FAMILIAR_THRESHOLD_S) : 5;
        centralityWindow = brSettings.contains(CENTRALITY_WINDOW_S) ? brSettings.getInt(CENTRALITY_WINDOW_S) : 21600;
        reputationEnabled = brSettings.contains(REPUTATION_ENABLED_S) ? brSettings.getBoolean(REPUTATION_ENABLED_S) : true;
        greyThreshold = brSettings.contains(GREY_THRESHOLD_S) ? brSettings.getDouble(GREY_THRESHOLD_S) : 0.4;
        blackThreshold = brSettings.contains(BLACK_THRESHOLD_S) ? brSettings.getDouble(BLACK_THRESHOLD_S) : 0.2;
        
        initDataStructures();
    }
    
    protected BubbleRapRouter(BubbleRapRouter r) {
        super(r);
        this.familiarThreshold = r.familiarThreshold;
        this.centralityWindow = r.centralityWindow;
        this.reputationEnabled = r.reputationEnabled;
        this.greyThreshold = r.greyThreshold;
        this.blackThreshold = r.blackThreshold;
        
        initDataStructures();
    }
    
    private void initDataStructures() {
        this.encounterCount = new HashMap<>();
        this.familiarSet = new HashSet<>();
        this.encounterTimes = new HashMap<>();
        this.localRank = 0;
        this.reputationScores = new HashMap<>();
        this.garbageDetected = 0;
        this.messagesFromBlacklisted = 0;
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        
        if (con.isUp()) {
            DTNHost otherHost = con.getOtherNode(getHost());
            
            // Increment encounter count
            int count = encounterCount.getOrDefault(otherHost, 0) + 1;
            encounterCount.put(otherHost, count);
            
            // Record encounter timestamp
            double currentTime = SimClock.getTime();
            encounterTimes.putIfAbsent(otherHost, new ArrayList<Double>());
            encounterTimes.get(otherHost).add(currentTime);
            
            // Update familiar set
            if (count >= familiarThreshold) {
                familiarSet.add(otherHost);
            }
            
            // Update localRank (count unique encounters in time window)
            updateLocalRank();
            
            // Exchange information with the other router
            MessageRouter otherRouter = otherHost.getRouter();
            if (otherRouter instanceof BubbleRapRouter) {
                BubbleRapRouter otherBubble = (BubbleRapRouter) otherRouter;
                
                // Exchange reputation scores (merge with averaging)
                if (reputationEnabled) {
                    Map<DTNHost, Double> otherRep = otherBubble.getReputationScores();
                    for (Map.Entry<DTNHost, Double> entry : otherRep.entrySet()) {
                        DTNHost host = entry.getKey();
                        double otherScore = entry.getValue();
                        
                        if (host == getHost()) continue;
                        
                        double myScore = this.getReputation(host);
                        if (this.reputationScores.containsKey(host)) {
                            this.reputationScores.put(host, (myScore + otherScore) / 2.0);
                        } else {
                            this.reputationScores.put(host, otherScore);
                        }
                    }
                }
            }
        }
    }
    
    private void updateLocalRank() {
        double currentTime = SimClock.getTime();
        int uniqueEncounters = 0;
        
        for (Map.Entry<DTNHost, List<Double>> entry : encounterTimes.entrySet()) {
            List<Double> times = entry.getValue();
            // Remove old entries
            times.removeIf(t -> currentTime - t > centralityWindow);
            if (!times.isEmpty()) {
                uniqueEncounters++;
            }
        }
        
        this.localRank = uniqueEncounters;
    }

    @Override
    public void update() {
        super.update();
        if (!canStartTransfer() || isTransferring()) {
            return;
        }
        
        if (exchangeDeliverableMessages() != null) {
            return;
        }
        
        tryBubbleRapForwarding();
    }
    
    protected void tryBubbleRapForwarding() {
        List<Tuple<Message, Connection>> candidates = new ArrayList<>();
        Collection<Message> messages = getMessageCollection();
        
        for (Connection con : getConnections()) {
            DTNHost otherHost = con.getOtherNode(getHost());
            MessageRouter otherRouterBase = otherHost.getRouter();
            
            if (!(otherRouterBase instanceof BubbleRapRouter)) {
                continue;
            }
            
            BubbleRapRouter otherRouter = (BubbleRapRouter) otherRouterBase;
            
            if (otherRouter.isTransferring()) {
                continue;
            }
            
            if (reputationEnabled && isBlackListed(otherHost)) {
                continue;
            }
            
            for (Message m : messages) {
                if (otherRouter.hasMessage(m.getId())) {
                    continue;
                }
                
                boolean shouldForward = false;
                DTNHost dest = m.getTo();
                
                if (otherRouter.getFamiliarSet().contains(dest)) {
                    shouldForward = true;
                } else if (otherRouter.getLocalRank() > this.localRank) {
                    shouldForward = true;
                }
                
                if (shouldForward) {
                    candidates.add(new Tuple<>(m, con));
                }
            }
        }
        
        if (candidates.isEmpty()) {
            return;
        }
        
        // Sort candidates by other node's localRank (descending), penalizing greylisted nodes
        candidates.sort(new Comparator<Tuple<Message, Connection>>() {
            @Override
            public int compare(Tuple<Message, Connection> t1, Tuple<Message, Connection> t2) {
                DTNHost h1 = t1.getValue().getOtherNode(getHost());
                DTNHost h2 = t2.getValue().getOtherNode(getHost());
                BubbleRapRouter r1 = (BubbleRapRouter) h1.getRouter();
                BubbleRapRouter r2 = (BubbleRapRouter) h2.getRouter();
                
                int rank1 = r1.getLocalRank();
                int rank2 = r2.getLocalRank();
                
                // Penalize greylisted nodes: halve their effective rank
                if (reputationEnabled && isGreyListed(h1)) {
                    rank1 = rank1 / 2;
                }
                if (reputationEnabled && isGreyListed(h2)) {
                    rank2 = rank2 / 2;
                }
                
                return Integer.compare(rank2, rank1);
            }
        });
        
        tryMessagesForConnected(candidates);
    }

    @Override
    protected int checkReceiving(Message m, DTNHost from) {
        if (reputationEnabled) {
            if (isBlackListed(from)) {
                messagesFromBlacklisted++;
                return DENIED_POLICY;
            }
            
            if (m.getId().startsWith("GARB_")) {
                garbageDetected++;
                decreaseReputation(from, 0.3);
                return DENIED_POLICY;
            }
        }
        return super.checkReceiving(m, from);
    }
    
    public int getLocalRank() {
        return localRank;
    }
    
    public Set<DTNHost> getFamiliarSet() {
        return new HashSet<>(familiarSet);
    }
    
    public double getReputation(DTNHost host) {
        return reputationScores.getOrDefault(host, 1.0);
    }
    
    public void decreaseReputation(DTNHost host, double amount) {
        double current = getReputation(host);
        reputationScores.put(host, Math.max(0.0, current - amount));
    }
    
    public void increaseReputation(DTNHost host, double amount) {
        double current = getReputation(host);
        reputationScores.put(host, Math.min(1.0, current + amount));
    }
    
    public boolean isBlackListed(DTNHost host) {
        return getReputation(host) < blackThreshold;
    }
    
    public boolean isGreyListed(DTNHost host) {
        return getReputation(host) < greyThreshold;
    }
    
    public Map<DTNHost, Double> getReputationScores() {
        return new HashMap<>(reputationScores);
    }

    @Override
    public RoutingInfo getRoutingInfo() {
        RoutingInfo top = super.getRoutingInfo();
        RoutingInfo ri = new RoutingInfo("BubbleRap Routing Info");
        
        ri.addMoreInfo(new RoutingInfo("Community Size (familiar): " + familiarSet.size()));
        ri.addMoreInfo(new RoutingInfo("Local Rank: " + localRank));
        
        if (reputationEnabled) {
            RoutingInfo repInfo = new RoutingInfo("Reputation Scores");
            for (Map.Entry<DTNHost, Double> e : reputationScores.entrySet()) {
                repInfo.addMoreInfo(new RoutingInfo(e.getKey() + " : " + String.format("%.2f", e.getValue())));
            }
            ri.addMoreInfo(repInfo);
        }
        
        top.addMoreInfo(ri);
        return top;
    }

    @Override
    public MessageRouter replicate() {
        return new BubbleRapRouter(this);
    }
}
