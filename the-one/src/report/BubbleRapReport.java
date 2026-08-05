package report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.DTNHost;
import core.Message;
import core.MessageListener;

public class BubbleRapReport extends Report implements MessageListener {
    private int nrofCreated;
    private int nrofDelivered;
    private int nrofDropped;
    private int nrofRelayed;
    private int nrofGarbageCreated;
    private int nrofGarbageDropped;
    private int nrofLegitimateCreated;
    private int nrofLegitimateDelivered;
    private List<Double> latencies;
    private Map<String, Double> creationTimes;
    private int nrofBlacklistRejections;
    private int nrofBufferFullRejections;
    private int nrofStarted;
    private int nrofAborted;

    public BubbleRapReport() {
        init();
    }

    @Override
    protected void init() {
        super.init();
        this.nrofCreated = 0;
        this.nrofDelivered = 0;
        this.nrofDropped = 0;
        this.nrofRelayed = 0;
        this.nrofGarbageCreated = 0;
        this.nrofGarbageDropped = 0;
        this.nrofLegitimateCreated = 0;
        this.nrofLegitimateDelivered = 0;
        this.latencies = new ArrayList<>();
        this.creationTimes = new HashMap<>();
        this.nrofBlacklistRejections = 0;
        this.nrofBufferFullRejections = 0;
        this.nrofStarted = 0;
        this.nrofAborted = 0;
    }

    @Override
    public void newMessage(Message m) {
        if (isWarmup()) {
            addWarmupID(m.getId());
            return;
        }

        this.nrofCreated++;
        this.creationTimes.put(m.getId(), getSimTime());

        if (m.getId().startsWith("GARB_")) {
            this.nrofGarbageCreated++;
        } else {
            this.nrofLegitimateCreated++;
        }
    }

    @Override
    public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean finalTarget) {
        if (isWarmupID(m.getId())) {
            return;
        }

        this.nrofRelayed++;
        if (finalTarget) {
            this.nrofDelivered++;
            if (!m.getId().startsWith("GARB_")) {
                this.nrofLegitimateDelivered++;
                this.latencies.add(getSimTime() - this.creationTimes.get(m.getId()));
            }
        }
    }

    @Override
    public void messageDeleted(Message m, DTNHost where, boolean dropped) {
        if (isWarmupID(m.getId())) {
            return;
        }

        if (dropped) {
            this.nrofDropped++;
            if (m.getId().startsWith("GARB_")) {
                this.nrofGarbageDropped++;
            }
            this.nrofBufferFullRejections++;
        }
    }

    @Override
    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
        if (isWarmupID(m.getId())) {
            return;
        }

        this.nrofAborted++;
        this.nrofBlacklistRejections++;
    }

    @Override
    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
        if (isWarmupID(m.getId())) {
            return;
        }

        this.nrofStarted++;
    }

    @Override
    public void done() {
        write("Bubble Rap Report for scenario " + getScenarioName() + "\n" +
              "sim_time: " + format(getSimTime()));

        double deliveryProb = 0;
        double overheadRatio = Double.NaN;
        double legitimateDeliveryProb = 0;
        double dropRate = 0;

        if (this.nrofCreated > 0) {
            deliveryProb = (1.0 * this.nrofDelivered) / this.nrofCreated;
        }
        
        if (this.nrofDelivered > 0) {
            overheadRatio = (1.0 * (this.nrofRelayed - this.nrofDelivered)) / this.nrofDelivered;
        }
        
        if (this.nrofLegitimateCreated > 0) {
            legitimateDeliveryProb = (1.0 * this.nrofLegitimateDelivered) / this.nrofLegitimateCreated;
        }

        if (this.nrofCreated > 0) {
            dropRate = (1.0 * this.nrofDropped) / this.nrofCreated;
        }

        String statsText = 
            "created: " + this.nrofCreated + "\n" +
            "started: " + this.nrofStarted + "\n" +
            "relayed: " + this.nrofRelayed + "\n" +
            "delivered: " + this.nrofDelivered + "\n" +
            "dropped: " + this.nrofDropped + "\n" +
            "delivery_prob: " + format(deliveryProb) + "\n" +
            "overhead_ratio: " + format(overheadRatio) + "\n" +
            "latency_avg: " + getAverage(this.latencies) + "\n" +
            "latency_med: " + getMedian(this.latencies) + "\n" +
            "garbage_created: " + this.nrofGarbageCreated + "\n" +
            "garbage_dropped: " + this.nrofGarbageDropped + "\n" +
            "legitimate_created: " + this.nrofLegitimateCreated + "\n" +
            "legitimate_delivered: " + this.nrofLegitimateDelivered + "\n" +
            "legitimate_delivery_prob: " + format(legitimateDeliveryProb) + "\n" +
            "drop_rate: " + format(dropRate);

        write(statsText);
        super.done();
    }
}
