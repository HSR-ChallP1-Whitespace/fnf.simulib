package com.zuehlke.carrera.simulator.model.racetrack;

import com.zuehlke.carrera.relayapi.messages.PenaltyMessage;
import com.zuehlke.carrera.relayapi.messages.RoundTimeMessage;
import com.zuehlke.carrera.relayapi.messages.SensorEvent;
import com.zuehlke.carrera.relayapi.messages.VelocityMessage;
import com.zuehlke.carrera.simulator.config.SimulatorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a virtual RaceTrack. Since it's stateful, it must only be used within Actors
 * <p>
 * Created by wgiersche on 06/09/14.
 */
public class VirtualRaceTrack {

    private static final Logger LOG = LoggerFactory.getLogger(VirtualRaceTrack.class);
    private static final int FLOATING_VELOCITY_AVERAGE_SIZE = 1;

    private final List<TrackEventListener> trackEventListeners = new ArrayList<>();
    private final List<VelocityListener> velocityListeners = new ArrayList<>();
    private final List<PenaltyListener> penaltyListeners = new ArrayList<>();
    private final List<RoundTimeListener> roundPassedListeners = new ArrayList<>();
    private final String raceTrackId;
    private final int[] acc = new int[3];
    private final int[] gyr = new int[3];
    private final int[] mag = new int[3];
    private final Map<String, TrackDesign> trackDesignMap = new HashMap<>();
    private double position = 0;
    private double velocity = 0;
    private int currentPower = 0;
    private TrackDesign design;
    private int period;
    private int roundNumber = 1;
    private TrackPhysicsModel trackPhysicsModel;
    private RazorModel2 razorModel;
    private TrackSection recentSection;
    private FloatingAverage averageVelocity = new FloatingAverage(FLOATING_VELOCITY_AVERAGE_SIZE);
    private double lastMeasuredVelocity = 0;
    private SimulatorProperties properties;

    public VirtualRaceTrack(String raceTrackId, TrackPhysicsModel trackPhysicsModel, SimulatorProperties properties) {
        this.properties = properties;
        this.razorModel = new RazorModel2(properties.getRazor());
        this.raceTrackId = raceTrackId;
        this.trackPhysicsModel = trackPhysicsModel;
        prepareDesigns();
    }


    public TrackDesign design() {
        design = new TrackDesign();
        return design;
    }

    public TrackDesign defaultDesign() {
        design = new TrackDesign(true);
        return design;
    }

    public void doSomeRounds(int numRounds) {

        roundNumber = 1;

        while (roundNumber <= numRounds) {
            try {
                Thread.sleep(period);
            } catch (InterruptedException e) {
                LOG.warn("ignoring InterruptedException.");
            }
            forward(period);
        }

    }


    public void addListener(VelocityListener listener) {
        velocityListeners.add(listener);
    }

    public void addListener(TrackEventListener listener) {
        trackEventListeners.add(listener);
    }

    public void addListener(PenaltyListener listener) {
        penaltyListeners.add(listener);
    }

    public void addListener(RoundTimeListener listener) {
        roundPassedListeners.add(listener);
    }

    /**
     * Let time pass (car will move if velocity is non-zero).
     *
     * @param millies the number of milliseconds to pass
     */
    public void forward(int millies) {

        calculateNewPosition(millies);

        fireRaceTrackEvent(getCurrentSensorEvent());

        TrackSection section = design.findSectionAt(position);
        if (recentSection != section) {
            recentSection = section;
            if (section instanceof LightBarrier) {

                LightBarrier barrier = (LightBarrier) section;
                long now = System.currentTimeMillis();

                if (barrier.isRoundStart()) {
                    fireRoundPassedMessage();
                }

                VelocityMessage message = new VelocityMessage(raceTrackId, now,
                        averageVelocity.currentAverage(), barrier.getId());
                fireVelocityMessage(message);
                lastMeasuredVelocity = message.getVelocity();

                double limit = ((LightBarrier) section).getSpeedLimit();
                if (message.getVelocity() > limit) {
                    firePenaltyMessage(new PenaltyMessage(raceTrackId,
                            barrier.getId(), message.getVelocity(), limit, properties.getPenalty()));
                }
            }
        }
    }


    /**
     * Returns the current sensor-event
     * This will create simulated acc1 and gyr2 values depending on the current position
     * and add some random noise.
     *
     * @return the newly generated sensor event
     */
    private SensorEvent getCurrentSensorEvent() {
        long now = System.currentTimeMillis();
        gyr[2] = (int) razorModel.gyro_z(getDesign().invRad(position), velocity, now);
        return new SensorEvent(raceTrackId, acc, gyr, mag, now);
    }


    private void calculateNewPosition(int millies) {

        float ri = (float) design.invRad(position);
        Coordinates c = trackPhysicsModel.propagation((float) velocity, ri, currentPower, millies);
        position += c.getPosition();
        velocity += c.getVelocity();

        averageVelocity.nextAverage(velocity);


        if (position > design.getLength()) {
            roundNumber++;
            position -= design.getLength();
        }
    }

    /**
     * set the car to a particular position
     *
     * @param position the trajectorial position of the car on the current design
     */
    public void setPosition(double position) {
        this.position = position;
    }

    public void setPeriod(int periodInMillies) {
        this.period = periodInMillies;
    }

    public TrackDesign getDesign() {
        return design;
    }

    public Anchor calculateCurrentAnchor() {

        return design.findAnchorAt(position);
    }


    private void fireRaceTrackEvent(SensorEvent arg) {
        for (TrackEventListener li : trackEventListeners) {
            TrackEvent event = new TrackEvent(arg, (float) getDesign().invRad(position),
                    (float) velocity, (float) position);
            li.onTrackEvent(event);
        }
    }

    private void fireVelocityMessage(VelocityMessage message) {
        for (VelocityListener li : velocityListeners) {
            li.onVelocityMessage(message);
        }
    }

    private void firePenaltyMessage(PenaltyMessage message) {
        for (PenaltyListener li : penaltyListeners) {
            li.onPenaltyMessage(message);
        }
    }

    private void fireRoundPassedMessage() {
        RoundTimeMessage message = new RoundTimeMessage(raceTrackId, System.currentTimeMillis());
        for (RoundTimeListener li : roundPassedListeners) {
            li.onRoundPassed(message);
        }
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public void start() {
        roundNumber = 1;
        position = 0;
    }

    public void reset() {
        position = 0;
        velocity = 0;
        currentPower = 0;
        lastMeasuredVelocity = 0;
        roundNumber = 1;
    }

    public int getPower() {
        return currentPower;
    }

    public void setPower(int p) {
        currentPower = p;

    }

    public void changePower(int delta) {
        currentPower += delta;
        if (currentPower > 255) {
            currentPower = 255;
        } else if (currentPower < 0) {
            currentPower = 0;
        }
    }

    public double getLastMeasuredVelocity() {
        return lastMeasuredVelocity;
    }

    public void selectDesign(String trackDesign) {
        TrackDesign newDesign = trackDesignMap.get(trackDesign);
        if (newDesign != null) {
            this.design = newDesign;
        }
    }

    private void prepareDesigns() {

        trackDesignMap.put("Budapest",
                new TrackDesign()
                        .straight(180)
                        .lightBarrier("BA01", 40, 300).asRoundStart()
                        .curve(30, -1)
                        .curve(30, 1)
                        .curve(50, -4)
                        .curve(50, 2)
                        .straight(60)
                        .curve(30, 2)
                        .curve(30, -4)
                        .straight(340)
                        .lightBarrier("BA02", 40, 300)
                        .curve(30, -4)
                        .lightBarrier("BA03", 40, 300)
                        .straight(160)
                        .lightBarrier("BA04", 40, 300)
                        .curve(50, 2)
                        .straight(60)
                        .curve(50, 2)
                        .lightBarrier("BA05", 40, 300)
                        .straight(100)
                        .lightBarrier("BA06", 40, 300)
                        .curve(30, -1)
                        .curve(30, 1)
                        .straight(40)
                        .curve(30, -2)
                        .curve(50, -2)
                        .straight(140)
                        .create());

        trackDesignMap.put("Berlin",
                new TrackDesign()
                        .straight(100).lightBarrier("BA01", 20, 300).asRoundStart().curve(30, -4).lightBarrier
                        ("BA02", 20, 300)
                        .straight(200).lightBarrier("BA03", 20, 300).curve(30, -4).lightBarrier("BA04", 20, 300)
                        .straight(100).create());

        double radius = 28;
        trackDesignMap.put("Oerlikon",
                new TrackDesign()
                        .straight(120)
                        .lightBarrier("48B0", 20, 300).asRoundStart()
                        .curve(radius, -4)
                        .lightBarrier("3ABE", 20, 300)
                        .straight(240)
                        .lightBarrier("7DF5", 20, 400)
                        .straight(40)
                        .lightBarrier("1FDB", 30, 300)
                        .curve(radius, -1)
                        .curve(radius, 1)
                        .curve(radius, -2)
                        .curve(radius, 2)
                        .curve(radius, -4)
                        .straight(80)
                        .lightBarrier("48BD", 20, 350)
                        .curve(radius, -1)
                        .curve(radius, 1)
                        .curve(radius, -3)
                        .curve(radius, 2)
                        .curve(radius, -1)
                        .straight(40)
                        .curve(radius, 1)
                        .curve(radius, -1)
                        .curve(radius, 4)
                        .curve(radius, -1)
                        .curve(radius, 1)
                        .lightBarrier("48AB", 20, 300)
                        .straight(44) // correction
                        .curve(radius, -2)
                        .straight(20) // correction
                        .create());

        trackDesignMap.put("Hollywood",
                new TrackDesign()
                        .lightBarrier("48AB", 20, 300).asRoundStart()
                        .straight(160)
                        .lightBarrier("48BD", 20, 300)
                        .curve(radius, -2)
                                //.straight(100) this would be the originals
                        .straight(90)
                        .lightBarrier("dunnoyet", 20, 300)
                        .curve(radius, 2)
                        .curve(radius, -1)
                        .curve(radius, 2)
                        .curve(radius, -1)
                        .straight(45)
                        .curve(radius, 4)
                        .straight(80)
                        .curve(radius, -2)
                        .straight(55)
                        .curve(radius, -2)
                        .lightBarrier("", 20, 300)
                        .straight(120)
                        .lightBarrier("", 20, 300)
                        .curve(radius, -4)
                        .lightBarrier("", 20, 300)
                        .straight(335)
                                //.straight(360) this would be the original
                        .lightBarrier("", 20, 300)
                        .straight(70)
                        .lightBarrier("", 20, 300)
                        .curve(radius, -4)
                        .create());

    }
}
