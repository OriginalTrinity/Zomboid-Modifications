// Decompiled with Zomboid Decompiler v0.3.1 using Vineflower.
package zombie.vehicles;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.joml.Quaternionf;
import zombie.GameTime;
import zombie.characters.IsoPlayer;
import zombie.core.physics.WorldSimulation;
import zombie.core.znet.ZNetStatistics;
import zombie.debug.DebugType;
import zombie.network.GameClient;
import zombie.network.packets.vehicle.VehiclePhysicsPacket;

/**
 * Created by kroto on 1/17/2017.
 */
public class VehicleInterpolation {
    private static final ArrayDeque<VehicleInterpolationData> pool = new ArrayDeque<>();
    private static final List<VehicleInterpolationData> outdated = new ArrayList<>();
    private static final Quaternionf tempQuaternionA = new Quaternionf();
    private static final Quaternionf tempQuaternionB = new Quaternionf();
    private static final VehicleInterpolationData temp = new VehicleInterpolationData();
    final TreeSet<VehicleInterpolationData> buffer = new TreeSet<>();
    int delay;
    int history;
    int delayTarget;
    boolean buffering;
    float[] lastBuf1;
    boolean wasNull;
    long lastTime = -1L;
    long lastTimeA = -1L;
    long recountedTime;
    boolean highPing;
    boolean wasHighPing;
    private byte getPointUpdateTimeout;
    private final byte getPointUpdatePer = 10;

    private static final int BASE_DELAY = 500;
    private static final int MAX_DELAY = 1500;
    private static final int UNDERRUN_BOOST_STEP = 250;
    private static final int UNDERRUN_BOOST_MAX = 1000;
    private static final long UNDERRUN_BOOST_HOLD = 30000L;
    private static final float UNDERRUN_MIN_SPEED = 1.0F;
    private static final boolean LOG_DIAGNOSTICS = true;
    private static final long DIAGNOSTICS_INTERVAL = 10000L;
    private static final long SEND_GAP_THRESHOLD = 250L;
    private BaseVehicle vehicle;
    private int underrunBoost;
    private long lastUnderrunMillis;
    private long lastArrivalMillis = -1L;
    private long lastArrivalDataTime = -1L;
    private long windowStartMillis = -1L;
    private int windowPackets;
    private long windowAgeSum;
    private long windowAgeMax;
    private long windowArrivalGapMax;
    private int windowSendGaps;
    private long windowSendGapMax;
    private int windowUnderruns;
    private int windowBursts;

    VehicleInterpolation() {
        this.reset();
        this.delay = 500;
        this.history = 800;
        this.delayTarget = this.delay;
    }

    public void reset() {
        this.buffering = true;
        this.clear();
    }

    public void clear() {
        if (!this.buffer.isEmpty()) {
            pool.addAll(this.buffer);
            this.buffer.clear();
            outdated.clear();
        }
    }

    public void clearLast() {
        this.wasNull = false;
        this.lastBuf1 = null;
        this.lastTime = -1L;
    }

    public void clearAll() {
        this.clear();
        this.clearLast();
    }

    public void update(long time) {
        temp.time = time - this.delay;
        VehicleInterpolationData dataA = this.buffer.floor(temp);

        for (VehicleInterpolationData data : this.buffer) {
            if (time - data.time > this.history && data != dataA) {
                outdated.add(data);
            }
        }

        outdated.forEach(this.buffer::remove);
        pool.addAll(outdated);
        outdated.clear();
        if (this.buffer.isEmpty()) {
            this.buffering = true;
        }
    }

    void getPointUpdate(VehicleInterpolation towedByInterpolation) {
        if (towedByInterpolation != null) {
            this.delay = towedByInterpolation.delay;
            this.history = towedByInterpolation.history;
            this.delayTarget = towedByInterpolation.delayTarget;
            this.highPing = towedByInterpolation.highPing;
            this.wasHighPing = towedByInterpolation.wasHighPing;
        } else if (this.getPointUpdateTimeout++ >= 10) {
            float dt = WorldSimulation.instance.periodSec * 10.0F;
            this.getPointUpdateTimeout = 0;
            int lastPing = GameClient.connection.getLastPing();
            boolean isHighPing = lastPing > 290;
            if (this.highPing && !isHighPing) {
                this.wasHighPing = true;
            }

            if (isHighPing) {
                this.wasHighPing = false;
            }

            this.highPing = isHighPing;
            if (this.delay != this.delayTarget) {
                int delayStep = Math.max(1, (int)(500.0F * dt / 3.0F));
                if (this.delay < this.delayTarget) {
                    if (this.wasHighPing) {
                        this.wasHighPing = false;
                    }

                    if (this.highPing) {
                        delayStep = this.delayTarget - this.delay;
                    }

                    int d = Math.min(this.delayTarget - this.delay, delayStep);
                    this.delay += d;
                    this.history += d;
                } else {
                    if (this.wasHighPing) {
                        delayStep = (int)(delayStep / 8.0F);
                        if (delayStep < 1) {
                            delayStep = 1;
                        }
                    }

                    int d = Math.min(this.delay - this.delayTarget, delayStep);
                    this.delay -= d;
                    this.history -= d;
                }
            }

            this.decayUnderrunBoost();
            int delayCap = this.highPing ? MAX_DELAY : Math.min(MAX_DELAY, BASE_DELAY + this.underrunBoost);

            if (this.delayTarget != delayCap) {
                if (this.delayTarget < delayCap) {
                    this.delayTarget = this.delayTarget + Math.max(1, (int)(delayCap * dt / 10.0F));
                    if (this.highPing) {
                        this.delayTarget = delayCap;
                    }
                } else {
                    this.delayTarget = this.delayTarget - Math.max(1, (int)(delayCap * dt / 10.0F));
                }
            }

            if (this.wasHighPing && !this.highPing && Math.abs(this.delay - this.delayTarget) < 10 && Math.abs(delayCap - this.delayTarget) < 10) {
                this.wasHighPing = false;
                this.delayTarget = delayCap;
            }
        }
    }

    private void interpolationDataCurrentAdd(BaseVehicle vehicle) {
        VehicleInterpolationData d = pool.isEmpty() ? new VehicleInterpolationData() : pool.pop();
        d.time = GameTime.getServerTimeMills() - this.delay;
        d.x = vehicle.jniTransform.origin.x + WorldSimulation.instance.offsetX;
        d.y = vehicle.jniTransform.origin.z + WorldSimulation.instance.offsetY;
        d.z = vehicle.jniTransform.origin.y;
        Quaternionf q = vehicle.jniTransform.getRotation(new Quaternionf());
        d.qx = q.x;
        d.qy = q.y;
        d.qz = q.z;
        d.qw = q.w;
        d.vx = vehicle.jniLinearVelocity.x;
        d.vy = vehicle.jniLinearVelocity.y;
        d.vz = vehicle.jniLinearVelocity.z;
        d.engineSpeed = (float)vehicle.getEngineSpeed();
        d.throttle = vehicle.throttle;
        d.setNumWheels((short)vehicle.wheelInfo.length);

        for (int i = 0; i < d.wheelsCount; i++) {
            d.wheelSteering[i] = vehicle.wheelInfo[i].steering;
            d.wheelRotation[i] = vehicle.wheelInfo[i].rotation;
            d.wheelSkidInfo[i] = vehicle.wheelInfo[i].skidInfo;
            if (vehicle.wheelInfo[i].suspensionLength > 0.0F) {
                d.wheelSuspensionLength[i] = vehicle.wheelInfo[i].suspensionLength;
            } else {
                d.wheelSuspensionLength[i] = 0.3F;
            }
        }

        this.buffer.add(d);
    }

    public void interpolationDataAdd(BaseVehicle vehicle, VehicleInterpolationData data, long currentTime) {
        this.vehicle = vehicle;
        this.recordArrival(data, currentTime);
        if (this.buffer.isEmpty()) {
            this.interpolationDataCurrentAdd(vehicle);
        }

        VehicleInterpolationData d = pool.isEmpty() ? new VehicleInterpolationData() : pool.pop();
        d.copy(data);
        this.buffer.add(d);
        this.update(currentTime);
    }

    public boolean interpolationDataGet(float[] buf1, float[] buf2) {
        return this.interpolationDataGet(buf1, buf2, null);
    }

    public boolean interpolationDataGet(float[] buf1, float[] buf2, VehicleInterpolation towedByInterpolation) {
        if (towedByInterpolation != null) {
            this.delay = towedByInterpolation.delay;
        }

        long time = WorldSimulation.instance.time - this.delay;
        return this.interpolationDataGet(buf1, buf2, time, towedByInterpolation);
    }

    public VehicleInterpolationData getLastAddedInterpolationPoint() {
        try {
            return this.buffer.last();
        } catch (Exception e) {
            return null;
        }
    }

    public void setDelayLength(float d) {
        this.delayTarget = (int)(d * 500.0F);
    }

    public boolean isDelayLengthIncreased() {
        return this.delayTarget > 500;
    }

    public boolean interpolationDataGet(float[] buf1, float[] buf2, long time, VehicleInterpolation towedByInterpolation) {
        this.getPointUpdate(towedByInterpolation);
        temp.time = time;
        VehicleInterpolationData dataB = this.buffer.higher(temp);
        VehicleInterpolationData dataA = this.buffer.floor(temp);
        if (this.buffering) {
            if (this.buffer.size() < 2 || dataB == null || dataA == null) {
                return false;
            }

            this.buffering = false;
        } else if (this.buffer.isEmpty()) {
            this.reset();
            return false;
        }

        int n = 0;
        if (dataB == null) {
            if (dataA == null) {
                this.reset();
                return false;
            }

            this.onUnderrun(dataA, towedByInterpolation == null);
            this.wasNull = true;
            this.lastTimeA = -1L;
            this.lastTime = dataA.time;
            this.recountedTime = this.lastTime;
            buf2[0] = dataA.engineSpeed;
            buf2[1] = dataA.throttle;
            buf1[n++] = dataA.x;
            buf1[n++] = dataA.y;
            buf1[n++] = dataA.z;
            buf1[n++] = dataA.qx;
            buf1[n++] = dataA.qy;
            buf1[n++] = dataA.qz;
            buf1[n++] = dataA.qw;
            buf1[n++] = dataA.vx;
            buf1[n++] = dataA.vy;
            buf1[n++] = dataA.vz;
            buf1[n++] = dataA.wheelsCount;

            for (int i = 0; i < dataA.wheelsCount; i++) {
                buf1[n++] = dataA.wheelSteering[i];
                buf1[n++] = dataA.wheelRotation[i];
                buf1[n++] = dataA.wheelSkidInfo[i];
                buf1[n++] = dataA.wheelSuspensionLength[i];
            }

            this.lastBuf1 = new float[buf1.length];

            for (int i = 0; i < buf1.length; i++) {
                this.lastBuf1[i] = buf1[i];
            }

            this.reset();
            return true;
        } else if (dataA != null && (Math.abs(dataB.time - dataA.time) >= 10L || this.wasNull) && (!this.wasNull || dataB.time - this.lastTime >= 10L)) {
            if (this.lastTimeA == -1L) {
                this.lastTimeA = dataA.time;
            }

            if (this.lastTimeA != dataA.time && this.wasNull) {
                this.lastTimeA = dataA.time;
                this.wasNull = false;
            }

            float tempTimeM;
            if (this.wasNull) {
                if (time - this.recountedTime > 20L) {
                    long timeChunk = (dataB.time - this.lastTime) / 7L;
                    this.recountedTime += timeChunk > 20L ? timeChunk : 20L;
                } else {
                    this.recountedTime = time;
                }

                tempTimeM = (float)(this.recountedTime - this.lastTime) / (float)(dataB.time - this.lastTime);
            } else {
                tempTimeM = (float)(time - dataA.time) / (float)(dataB.time - dataA.time);
            }

            float timeM = tempTimeM;
            buf2[0] = (dataB.engineSpeed - dataA.engineSpeed) * timeM + dataA.engineSpeed;
            buf2[1] = (dataB.throttle - dataA.throttle) * timeM + dataA.throttle;
            if (this.wasNull) {
                buf1[n] = (dataB.x - this.lastBuf1[n]) * timeM + this.lastBuf1[n];
                buf1[++n] = (dataB.y - this.lastBuf1[n]) * timeM + this.lastBuf1[n];
                buf1[++n] = (dataB.z - this.lastBuf1[n]) * timeM + this.lastBuf1[n];
                n++;
                tempQuaternionA.set(dataA.qx, dataA.qy, dataA.qz, dataA.qw);
                tempQuaternionB.set(dataB.qx, dataB.qy, dataB.qz, dataB.qw);
                tempQuaternionA.nlerp(tempQuaternionB, timeM);
                buf1[n++] = tempQuaternionA.x;
                buf1[n++] = tempQuaternionA.y;
                buf1[n++] = tempQuaternionA.z;
                buf1[n++] = tempQuaternionA.w;
                buf1[n] = (dataB.vx - this.lastBuf1[n]) * timeM + this.lastBuf1[n];
                buf1[++n] = (dataB.vy - this.lastBuf1[n]) * timeM + this.lastBuf1[n];
                buf1[++n] = (dataB.vz - this.lastBuf1[n]) * timeM + this.lastBuf1[n];
                int var61 = ++n;
                n++;
                buf1[var61] = dataB.wheelsCount;

                for (int i = 0; i < dataB.wheelsCount; i++) {
                    buf1[n++] = (dataB.wheelSteering[i] - dataA.wheelSteering[i]) * timeM + dataA.wheelSteering[i];
                    buf1[n++] = (dataB.wheelRotation[i] - dataA.wheelRotation[i]) * timeM + dataA.wheelRotation[i];
                    buf1[n++] = (dataB.wheelSkidInfo[i] - dataA.wheelSkidInfo[i]) * timeM + dataA.wheelSkidInfo[i];
                    buf1[n++] = (dataB.wheelSuspensionLength[i] - dataA.wheelSuspensionLength[i]) * timeM + dataA.wheelSuspensionLength[i];
                }
            } else {
                buf1[n++] = (dataB.x - dataA.x) * timeM + dataA.x;
                buf1[n++] = (dataB.y - dataA.y) * timeM + dataA.y;
                buf1[n++] = (dataB.z - dataA.z) * timeM + dataA.z;
                tempQuaternionA.set(dataA.qx, dataA.qy, dataA.qz, dataA.qw);
                tempQuaternionB.set(dataB.qx, dataB.qy, dataB.qz, dataB.qw);
                tempQuaternionA.nlerp(tempQuaternionB, timeM);
                buf1[n++] = tempQuaternionA.x;
                buf1[n++] = tempQuaternionA.y;
                buf1[n++] = tempQuaternionA.z;
                buf1[n++] = tempQuaternionA.w;
                buf1[n++] = (dataB.vx - dataA.vx) * timeM + dataA.vx;
                buf1[n++] = (dataB.vy - dataA.vy) * timeM + dataA.vy;
                buf1[n++] = (dataB.vz - dataA.vz) * timeM + dataA.vz;
                buf1[n++] = dataB.wheelsCount;

                for (int i = 0; i < dataB.wheelsCount; i++) {
                    buf1[n++] = (dataB.wheelSteering[i] - dataA.wheelSteering[i]) * timeM + dataA.wheelSteering[i];
                    buf1[n++] = (dataB.wheelRotation[i] - dataA.wheelRotation[i]) * timeM + dataA.wheelRotation[i];
                    buf1[n++] = (dataB.wheelSkidInfo[i] - dataA.wheelSkidInfo[i]) * timeM + dataA.wheelSkidInfo[i];
                    buf1[n++] = (dataB.wheelSuspensionLength[i] - dataA.wheelSuspensionLength[i]) * timeM + dataA.wheelSuspensionLength[i];
                }
            }

            this.wasNull = false;
            return true;
        } else {
            return false;
        }
    }

    private void onUnderrun(VehicleInterpolationData newest, boolean adjustDelay) {
        long now = GameTime.getServerTimeMills();
        float speedSquared = newest.vx * newest.vx + newest.vy * newest.vy + newest.vz * newest.vz;
        int boostBefore = this.underrunBoost;
        if (adjustDelay && speedSquared >= UNDERRUN_MIN_SPEED) {
            this.underrunBoost = Math.min(UNDERRUN_BOOST_MAX, this.underrunBoost + UNDERRUN_BOOST_STEP);
            this.lastUnderrunMillis = now;
            int target = Math.min(MAX_DELAY, BASE_DELAY + this.underrunBoost);

            if (this.delayTarget < target) {
                this.delayTarget = target;
            }

            if (this.delay < target) {
                this.history += target - this.delay;
                this.delay = target;
            }
        }

        if (this.isDiagnosticsTarget()) {
            this.windowUnderruns++;
            DebugType.Multiplayer.println(
                    "[VehicleSync] UNDERRUN vehicle=%s newestAge=%d sinceArrival=%d delay=%d target=%d boost=%d->%d ping=%d speed=%.1fkm/h buffer=%d",
                    this.vehicle.getScriptName(),
                    now - newest.time,
                    this.lastArrivalMillis < 0L ? -1L : now - this.lastArrivalMillis,
                    this.delay,
                    this.delayTarget,
                    boostBefore,
                    this.underrunBoost,
                    GameClient.connection.getLastPing(),
                    Math.sqrt(speedSquared) * 3.6f,
                    this.buffer.size());
        }
    }

    private void decayUnderrunBoost() {
        if (this.underrunBoost > 0) {
            long now = GameTime.getServerTimeMills();
            if (now - this.lastUnderrunMillis >= UNDERRUN_BOOST_HOLD) {
                this.underrunBoost = Math.max(0, this.underrunBoost - UNDERRUN_BOOST_STEP);
                this.lastUnderrunMillis = now;
            }
        }
    }

    private boolean isDiagnosticsTarget() {
        if (LOG_DIAGNOSTICS && this.vehicle != null) {
            IsoPlayer player = IsoPlayer.getInstance();
            return player != null && player.getVehicle() == this.vehicle;
        }
        return false;
    }

    private void recordArrival(VehicleInterpolationData data, long now) {
        if (data instanceof VehiclePhysicsPacket) {
            if (this.isDiagnosticsTarget()) {
                long age = now - data.time;
                if (this.windowStartMillis < 0L) {
                    this.windowStartMillis = now;
                }

                this.windowPackets++;
                this.windowAgeSum += age;
                this.windowAgeMax = Math.max(this.windowAgeMax, age);
                if (this.lastArrivalMillis >= 0L) {
                    long arrivalGap = now - this.lastArrivalMillis;
                    this.windowArrivalGapMax = Math.max(this.windowArrivalGapMax, arrivalGap);
                    if (arrivalGap < 20L) {
                        this.windowBursts++;
                    }
                }

                if (this.lastArrivalDataTime >= 0L) {
                    long sendGap = data.time - this.lastArrivalDataTime;
                    this.windowSendGapMax = Math.max(this.windowSendGapMax, sendGap);
                    if (sendGap > SEND_GAP_THRESHOLD) {
                        this.windowSendGaps++;
                    }
                }

                if (now - this.windowStartMillis >= DIAGNOSTICS_INTERVAL) {
                    this.flushDiagnostics(now);
                }
            }

            this.lastArrivalMillis = now;
            this.lastArrivalDataTime = data.time;
        }
    }

    private void flushDiagnostics(long now) {
        ZNetStatistics stats = GameClient.connection.getStatistics();
        DebugType.Multiplayer.println(
                "[VehicleSync] vehicle=%s window=%dms packets=%d age avg=%d max=%d arrivalGapMax=%d sendGaps=%d sendGapMax=%d bursts=%d underruns=%d delay=%d target=%d boost=%d ping=%d/%d recv=%.1fKB/s loss=%.3f",
                this.vehicle.getScriptName(),
                now - this.windowStartMillis,
                this.windowPackets,
                this.windowAgeSum / this.windowPackets,
                this.windowAgeMax,
                this.windowArrivalGapMax,
                this.windowSendGaps,
                this.windowSendGapMax,
                this.windowBursts,
                this.windowUnderruns,
                this.delay,
                this.delayTarget,
                this.underrunBoost,
                GameClient.connection.getLastPing(),
                GameClient.connection.getAveragePing(),
                stats == null ? -1.0 : stats.lastActualBytesReceived / 1024.0,
                stats == null ? -1.0 : stats.packetlossLastSecond
        );
        this.windowStartMillis = now;
        this.windowPackets = 0;
        this.windowAgeSum = 0L;
        this.windowAgeMax = 0L;
        this.windowArrivalGapMax = 0L;
        this.windowSendGaps = 0;
        this.windowSendGapMax = 0L;
        this.windowUnderruns = 0;
        this.windowBursts = 0;
    }
}
