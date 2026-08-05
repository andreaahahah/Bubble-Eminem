package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import routing.util.RoutingInfo;
import util.Tuple;
import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;

public class BubbleRapInterestRouter extends BubbleRapRouter {
    public static final String NAMESPACE = "BubbleRapInterestRouter";
    
    public static final String NROF_INTERESTS_S = "nrofInterests";
    public static final String INTERESTS_S = "interests";
    public static final String INTEREST_TRUST_THRESHOLD_S = "interestTrustThreshold";
    
    private int nrofInterests;
    protected String[] availableInterests;
    private double interestTrustThreshold;
    
    private Map<String, Double> interestProfile;
    private Map<DTNHost, Map<String, Integer>> observedTopics;
    private Map<DTNHost, Double> interestTrust;
    
    public BubbleRapInterestRouter(Settings s) {
        super(s);
        Settings settings = new Settings(NAMESPACE);
        
        this.nrofInterests = settings.contains(NROF_INTERESTS_S) ? settings.getInt(NROF_INTERESTS_S) : 5;
        
        String interestsCsv = settings.contains(INTERESTS_S) ? settings.getSetting(INTERESTS_S) : "sport,news,music,tech,health";
        this.availableInterests = interestsCsv.split(",");
        
        this.interestTrustThreshold = settings.contains(INTEREST_TRUST_THRESHOLD_S) ? settings.getDouble(INTEREST_TRUST_THRESHOLD_S) : 0.3;
        
        this.interestProfile = new HashMap<>();
        this.observedTopics = new HashMap<>();
        this.interestTrust = new HashMap<>();
    }
    
    protected BubbleRapInterestRouter(BubbleRapInterestRouter r) {
        super(r);
        this.nrofInterests = r.nrofInterests;
        this.availableInterests = r.availableInterests;
        this.interestTrustThreshold = r.interestTrustThreshold;
        
        this.interestProfile = new HashMap<>();
        this.observedTopics = new HashMap<>();
        this.interestTrust = new HashMap<>();
    }
    
    @Override
    public void init(DTNHost host, List<MessageListener> mListeners) {
        super.init(host, mListeners);
        
        int primary = host.getAddress() % availableInterests.length;
        int next = (primary + 1) % availableInterests.length;
        int another = (primary + 2) % availableInterests.length;
        
        for (int i = 0; i < availableInterests.length; i++) {
            if (i == primary) {
                this.interestProfile.put(availableInterests[i], 0.5);
            } else if (i == next) {
                this.interestProfile.put(availableInterests[i], 0.3);
            } else if (i == another) {
                this.interestProfile.put(availableInterests[i], 0.2);
            } else {
                this.interestProfile.put(availableInterests[i], 0.0);
            }
        }
    }
    
    public Map<String, Double> getInterestProfile() {
        return new HashMap<>(this.interestProfile);
    }
    
    public double getInterestSimilarity(DTNHost other) {
        MessageRouter otherRouter = other.getRouter();
        if (!(otherRouter instanceof BubbleRapInterestRouter)) {
            return 0.0;
        }
        Map<String, Double> otherProfile = ((BubbleRapInterestRouter) otherRouter).getInterestProfile();
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (String interest : availableInterests) {
            double v1 = this.interestProfile.getOrDefault(interest, 0.0);
            double v2 = otherProfile.getOrDefault(interest, 0.0);
            dotProduct += v1 * v2;
            norm1 += v1 * v1;
            norm2 += v2 * v2;
        }
        
        if (norm1 == 0.0 || norm2 == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
    
    public double getMessageTopicMatch(Message m, DTNHost node) {
        Object prop = m.getProperty("interest");
        if (prop == null) {
            return 0.0;
        }
        String topic = prop.toString();
        
        MessageRouter router = node.getRouter();
        if (!(router instanceof BubbleRapInterestRouter)) {
            return 0.0;
        }
        Map<String, Double> profile = ((BubbleRapInterestRouter) router).getInterestProfile();
        return profile.getOrDefault(topic, 0.0);
    }
    
    @Override
    protected void tryBubbleRapForwarding() {
        List<Tuple<Message, Connection>> candidates = new ArrayList<>();
        Collection<Message> messages = getMessageCollection();
        
        double maxRank = 1.0;
        for (Connection con : getConnections()) {
            DTNHost otherHost = con.getOtherNode(getHost());
            MessageRouter router = otherHost.getRouter();
            if (router instanceof BubbleRapInterestRouter) {
                double rank = ((BubbleRapInterestRouter) router).getLocalRank();
                if (rank > maxRank) {
                    maxRank = rank;
                }
            }
        }
        
        for (Connection con : getConnections()) {
            DTNHost otherHost = con.getOtherNode(getHost());
            MessageRouter otherRouterBase = otherHost.getRouter();
            
            if (!(otherRouterBase instanceof BubbleRapInterestRouter)) {
                continue;
            }
            
            BubbleRapInterestRouter otherRouter = (BubbleRapInterestRouter) otherRouterBase;
            
            if (otherRouter.isTransferring()) {
                continue;
            }
            
            if (isBlackListed(otherHost)) {
                continue;
            }
            
            for (Message m : messages) {
                if (otherRouter.hasMessage(m.getId())) {
                    continue;
                }
                
                boolean shouldForward = false;
                DTNHost dest = m.getTo();
                
                double myMatch = getMessageTopicMatch(m, getHost());
                double otherMatch = getMessageTopicMatch(m, otherHost);
                
                if (otherRouter.getFamiliarSet().contains(dest)) {
                    shouldForward = true;
                } else if (otherRouter.getLocalRank() > this.getLocalRank()) {
                    shouldForward = true;
                } else if (otherMatch > myMatch) {
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
        
        final double finalMaxRank = maxRank;
        
        candidates.sort(new Comparator<Tuple<Message, Connection>>() {
            @Override
            public int compare(Tuple<Message, Connection> t1, Tuple<Message, Connection> t2) {
                DTNHost h1 = t1.getValue().getOtherNode(getHost());
                DTNHost h2 = t2.getValue().getOtherNode(getHost());
                
                BubbleRapInterestRouter r1 = (BubbleRapInterestRouter) h1.getRouter();
                BubbleRapInterestRouter r2 = (BubbleRapInterestRouter) h2.getRouter();
                
                double normalizedRank1 = r1.getLocalRank() / finalMaxRank;
                double normalizedRank2 = r2.getLocalRank() / finalMaxRank;
                
                double match1 = getMessageTopicMatch(t1.getKey(), h1);
                double match2 = getMessageTopicMatch(t2.getKey(), h2);
                
                double score1 = 0.6 * normalizedRank1 + 0.4 * match1;
                double score2 = 0.6 * normalizedRank2 + 0.4 * match2;
                
                return Double.compare(score2, score1);
            }
        });
        
        tryMessagesForConnected(candidates);
    }
    
    @Override
    protected int checkReceiving(Message m, DTNHost from) {
        Object prop = m.getProperty("interest");
        if (prop != null) {
            String topic = prop.toString();
            observedTopics.putIfAbsent(from, new HashMap<String, Integer>());
            Map<String, Integer> topics = observedTopics.get(from);
            topics.put(topic, topics.getOrDefault(topic, 0) + 1);
            
            int totalSent = 0;
            for (int count : topics.values()) {
                totalSent += count;
            }
            
            if (totalSent >= 10) {
                MessageRouter fromRouter = from.getRouter();
                if (fromRouter instanceof BubbleRapInterestRouter) {
                    Map<String, Double> fromProfile = ((BubbleRapInterestRouter) fromRouter).getInterestProfile();
                    int matchingTopics = 0;
                    for (Map.Entry<String, Integer> entry : topics.entrySet()) {
                        if (fromProfile.getOrDefault(entry.getKey(), 0.0) > 0.0) {
                            matchingTopics += entry.getValue();
                        }
                    }
                    
                    double consistency = (double) matchingTopics / totalSent;
                    if (consistency < 0.3) {
                        double currentTrust = interestTrust.getOrDefault(from, 1.0);
                        interestTrust.put(from, Math.max(0.0, currentTrust - 0.1));
                    }
                }
            }
        }
        
        double currentTrust = interestTrust.getOrDefault(from, 1.0);
        if (currentTrust < interestTrustThreshold) {
            return DENIED_POLICY;
        }
        
        return super.checkReceiving(m, from);
    }
    
    @Override
    public boolean createNewMessage(Message m) {
        List<String> topInterests = new ArrayList<>();
        for (Map.Entry<String, Double> entry : interestProfile.entrySet()) {
            if (entry.getValue() > 0.0) {
                topInterests.add(entry.getKey());
            }
        }
        
        if (!topInterests.isEmpty()) {
            // Deterministic seed from host address for reproducibility
            Random rand = new Random(getHost().getAddress());
            double totalWeight = 0.0;
            for (String interest : topInterests) {
                totalWeight += interestProfile.get(interest);
            }
            
            double r = rand.nextDouble() * totalWeight;
            double sum = 0.0;
            String selectedInterest = topInterests.get(0);
            
            for (String interest : topInterests) {
                sum += interestProfile.get(interest);
                if (r <= sum) {
                    selectedInterest = interest;
                    break;
                }
            }
            
            m.addProperty("interest", selectedInterest);
        }
        
        return super.createNewMessage(m);
    }
    
    @Override
    public RoutingInfo getRoutingInfo() {
        RoutingInfo top = super.getRoutingInfo();
        
        RoutingInfo interestInfo = new RoutingInfo("Interest Profile");
        for (Map.Entry<String, Double> entry : interestProfile.entrySet()) {
            interestInfo.addMoreInfo(new RoutingInfo(entry.getKey() + ": " + String.format("%.2f", entry.getValue())));
        }
        top.addMoreInfo(interestInfo);
        
        RoutingInfo trustInfo = new RoutingInfo("Interest Trust");
        for (Map.Entry<DTNHost, Double> entry : interestTrust.entrySet()) {
            trustInfo.addMoreInfo(new RoutingInfo(entry.getKey().toString() + ": " + String.format("%.2f", entry.getValue())));
        }
        top.addMoreInfo(trustInfo);
        
        return top;
    }
    
    @Override
    public MessageRouter replicate() {
        return new BubbleRapInterestRouter(this);
    }
}
