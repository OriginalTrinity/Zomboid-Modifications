package zombie.iso;

import gnu.trove.map.hash.TLongObjectHashMap;
import org.joml.Vector3f;
import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.math.PZMath;
import zombie.core.random.Rand;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderSnow;
import zombie.iso.weather.ClimateManager;
import zombie.network.GameServer;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SnowTracks {
    public static final byte FOOT_LEFT = 0;
    public static final byte FOOT_RIGHT = 1;
    public static final byte TIRE = 2;
    public static final int VARIANTS = 3;

    private static final float FOOT_LENGTH = 0.36f;
    private static final float FOOT_WIDTH = 0.18f;
    private static final float FOOT_SIDE = 0.075f;
    private static final float FOOT_FORWARD = 0.12f;
    private static final float ZOMBIE_RADIUS = 30.0f;

    private static final float TIRE_STEP = 0.4f;
    private static final float TIRE_MAX_WIDTH = 0.3f;
    private static final float RUT_FRACTION = 0.7f; // part of the tire texture's width the rut fills
    private static final float MIN_JOIN_DOT = 0.5f; // sharper turns than 60 degrees aren't joined
    private static final float PARKED_EPSILON = 0.005f;
    // Body height above its level, in levels. Vanilla puts vehicles high above open ground on level 0
    private static final float FLYING_HEIGHT = 1.0f;
    private static final float LIFT_OFF_HEIGHT = 0.3f; // above the lowest height seen on the level
    private static final float TELEPORT = 3.0f;
    private static final float SNOWFALL_COVER_RATE = 6.0f;
    private static final float DENSITY_CELL = 0.5f;
    private static final int DENSITY_PASSES = 2;
    private static final int MAX_PASS_SEGMENTS = 4;
    // A rut segment is skipped where a fresh one already runs, e.g. the rear wheel in the front wheel's rut
    private static final float DEDUP_GRID = 0.25f;
    private static final float DEDUP_ALONG = TIRE_STEP * 0.5f + 0.02f;
    private static final float DEDUP_SIDE = 0.08f;
    private static final float DEDUP_MIN_DOT = 0.95f;
    private static final float DEDUP_FRESH = 0.05f; // part of the lifetime
    // Snow carried on boots and tires onto bare ground: this many prints / rut segments, each fainter
    private static final int CARRY_STEPS = 4;
    private static final int CARRY_SEGMENTS = 6;
    private static final float CARRIED_HOURS = 3.0f;
    private static final float DENT_COVERAGE = 0.5f; // less snow than this under a track and it's carried snow instead
    private static final long REPLACED_FADE = 2000;
    private static final int PRUNE_SIZE = 4096;
    private static final int PURGE_FADED = 256;

    private static final Layer prints = new Layer(24.0f, 6000);
    private static final Layer tires = new Layer(12.0f, 8000);
    private static final TLongObjectHashMap<Pass[]> density = new TLongObjectHashMap<>();
    private static int densityPruneAt = PRUNE_SIZE;
    private static final TLongObjectHashMap<Track> rutGrid = new TLongObjectHashMap<>();
    private static int rutGridPruneAt = PRUNE_SIZE;
    private static double lastWorldAgeHours = -1.0;
    private static IsoCell lastCell;

    private SnowTracks() {}

    public static final class Track {
        private final float x, y;
        private final int z;
        private final float dirX, dirY, length, width;
        private final float strength; // 1 for dents, the amount left for carried snow
        private final boolean carried;
        private final byte type, variant;
        private final double createdAt;
        private long fadeOutAt;
        private boolean removed;
        // Half-width vectors of the rear and front edges; joined ruts share a mitred edge
        private float rearAcrossX, rearAcrossY, frontAcrossX, frontAcrossY;
        private final long createdMs = System.currentTimeMillis();
        private byte seenBy;
        private long seenAt;

        private Track(float x, float y, int z, float dirX, float dirY, float length, float width, float strength, boolean carried, byte type, byte variant, double createdAt) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dirX = dirX;
            this.dirY = dirY;
            this.length = length;
            this.width = width;
            this.strength = strength;
            this.carried = carried;
            this.type = type;
            this.variant = variant;
            this.createdAt = createdAt;
            this.setRearAcross(-dirY * width * 0.5f, dirX * width * 0.5f);
            this.setFrontAcross(-dirY * width * 0.5f, dirX * width * 0.5f);
        }

        public double getCreatedAt() {
            return this.createdAt;
        }

        public float getDirX() {
            return this.dirX;
        }

        public float getDirY() {
            return this.dirY;
        }

        public long getFadeOutAt() {
            return this.fadeOutAt;
        }

        private void setFadeOutAt(long fadeOutAt) {
            this.fadeOutAt = fadeOutAt;
        }

        public float getLength() {
            return this.length;
        }

        public boolean isRemoved() {
            return this.removed;
        }

        private void setRemoved(boolean removed) {
            this.removed = removed;
        }

        public float getStrength() {
            return this.strength;
        }

        public boolean isCarried() {
            return this.carried;
        }

        public byte getType() {
            return this.type;
        }

        public byte getVariant() {
            return this.variant;
        }

        public float getWidth() {
            return this.width;
        }

        public float getX() {
            return this.x;
        }

        public float getY() {
            return this.y;
        }

        public int getZ() {
            return this.z;
        }

        public float getReplacedFade(long now) {
            return this.fadeOutAt == 0 ? 1.0f : Math.max(0.0f, 1.0f - (float) (now - this.fadeOutAt) / REPLACED_FADE);
        }

        public float getRearAcrossX() {
            return this.rearAcrossX;
        }

        public float getRearAcrossY() {
            return this.rearAcrossY;
        }

        public float getFrontAcrossX() {
            return this.frontAcrossX;
        }

        public float getFrontAcrossY() {
            return this.frontAcrossY;
        }

        private void setRearAcross(float x, float y) {
            this.rearAcrossX = x;
            this.rearAcrossY = y;
        }

        private void setFrontAcross(float x, float y) {
            this.frontAcrossX = x;
            this.frontAcrossY = y;
        }

        public boolean isSeenBy(int playerIndex) {
            return (this.seenBy & 1 << playerIndex) != 0;
        }

        /** seenAt counts only for the first player to see the track: the fade-in is shared */
        public void markSeenBy(int playerIndex, long seenAt) {
            if (this.seenBy == 0) {
                this.seenAt = seenAt;
            }

            this.seenBy |= (byte) (1 << playerIndex);
        }

        public long getSeenAt() {
            return this.seenAt;
        }

        public long getCreatedMs() {
            return this.createdMs;
        }
    }

    public static final class Layer {
        private final float lifeTimeHours;
        private final int max;
        private double clock;

        private final ArrayDeque<Track> all;
        private final TLongObjectHashMap<ArrayDeque<Track>> byChunk;
        private int faded;
        private long nextPurgeAt;

        private Layer(float lifeTimeHours, int max) {
            this.lifeTimeHours = lifeTimeHours;
            this.max = max;
            this.all = new ArrayDeque<>();
            this.byChunk = new TLongObjectHashMap<>();
        }

        /** Part of the track's lifetime that has passed; carried snow melts sooner than dents fill. */
        public float getAge(Track track) {
            float age = (float) (this.clock - track.getCreatedAt());
            return track.isCarried() ? age * this.lifeTimeHours / CARRIED_HOURS : age;
        }

        public boolean isEmpty() {
            return this.all.isEmpty();
        }

        public void collect(int chunkX, int chunkY, int z, List<Track> out) {
            ArrayDeque<Track> list = this.byChunk.get(chunkKey(chunkX, chunkY));
            if (list != null) {
                for (Track track : list) {
                    if (track.z == z && !track.isRemoved()) {
                        out.add(track);
                    }
                }
            }
        }

        private void add(Track track) {
            this.all.addLast(track);
            long key = chunkKey(track.getX(), track.getY());
            ArrayDeque<Track> list = this.byChunk.get(key);
            if (list == null) {
                list = new ArrayDeque<>();
                this.byChunk.put(key, list);
            }
            list.addLast(track);
            while (this.all.size() > this.max) {
                this.removeOldest();
            }
        }

        private void removeOldest() {
            Track track = this.all.removeFirst();
            track.setRemoved(true);
            if (track.getFadeOutAt() != 0) {
                this.faded--;
            }

            long key = chunkKey(track.getX(), track.getY());
            ArrayDeque<Track> list = this.byChunk.get(key);
            if (list != null) {
                list.pollFirst();
                if (list.isEmpty()) {
                    this.byChunk.remove(key);
                }
            }
        }

        private void advance(float hours, float rate) {
            this.clock += hours / this.lifeTimeHours * rate;
            while (!this.all.isEmpty() && this.getAge(this.all.peekFirst()) >= 1.0f) {
                this.removeOldest();
            }
        }

        private void markFaded(Track track, long now) {
            track.setFadeOutAt(now);
            this.faded++;
        }

        // Drops tracks whose replaced fade is over; once per fade time at most
        private void purgeFaded(long now) {
            if (this.faded < PURGE_FADED || now < this.nextPurgeAt) return;

            this.nextPurgeAt = now + REPLACED_FADE;
            this.all.removeIf(track -> {
                if (track.getFadeOutAt() == 0 || now - track.getFadeOutAt() < REPLACED_FADE) return false;
                track.setRemoved(true);
                this.faded--;
                return true;
            });
            this.byChunk.retainEntries((_, list) -> {
                list.removeIf(Track::isRemoved);
                return !list.isEmpty();
            });
        }

        private void clear() {
            for (Track track : this.all) {
                track.setRemoved(true);
            }

            this.all.clear();
            this.byChunk.clear();
            this.clock = 0.0;
            this.faded = 0;
        }
    }

    // The segments one wheel lays in a density cell while crossing it
    private static final class Pass {
        private final WheelState owner;
        private final int wheel;
        private final double createdAt;
        private final ArrayList<Track> segments = new ArrayList<>(MAX_PASS_SEGMENTS);

        private Pass(WheelState owner, int wheel, double createdAt) {
            this.owner = owner;
            this.wheel = wheel;
            this.createdAt = createdAt;
        }

        private boolean isGone() {
            for (Track segment : this.segments) {
                if (!segment.isRemoved() && segment.getFadeOutAt() == 0) {
                    return false;
                }
            }
            return true;
        }

        private void fadeOut(long now) {
            for (Track segment : this.segments) {
                if (!segment.isRemoved() && segment.getFadeOutAt() == 0) {
                    tires.markFaded(segment, now);
                }
            }
        }
    }

    public static final class FootState {
        private boolean leftFoot;
        private int carry;
    }

    public static final class WheelState {
        private static final int MAX_WHEELS = 4;
        private final float[] lastX = new float[MAX_WHEELS];
        private final float[] lastY = new float[MAX_WHEELS];
        private final boolean[] valid = new boolean[MAX_WHEELS];
        private final Track[] lastTrack = new Track[MAX_WHEELS];
        private final int[] carry = new int[MAX_WHEELS];
        private float vehicleX = Float.NaN;
        private float vehicleY = Float.NaN;
        private int restLevel;
        private float restHeight = Float.NaN;

        private void reset() {
            this.breakPaths();
            Arrays.fill(this.carry, 0);
            this.vehicleX = Float.NaN;
            this.vehicleY = Float.NaN;
            this.restHeight = Float.NaN;
        }

        private void breakPaths() {
            Arrays.fill(this.valid, false);
            Arrays.fill(this.lastTrack, null);
        }

        private boolean isAirborne(int level, float height) {
            if (level != this.restLevel || Float.isNaN(this.restHeight)) {
                this.restLevel = level;
                this.restHeight = height;
            }

            this.restHeight = Math.min(this.restHeight, height);
            return height > FLYING_HEIGHT || height > this.restHeight + LIFT_OFF_HEIGHT;
        }

        /** Returns false if the vehicle hasn't moved since the last call. */
        private boolean updateVehiclePosition(float x, float y) {
            if (Math.abs(x - this.vehicleX) < PARKED_EPSILON && Math.abs(y - this.vehicleY) < PARKED_EPSILON) {
                return false;
            }

            this.vehicleX = x;
            this.vehicleY = y;
            return true;
        }

        private float[] getLastX() {
            return this.lastX;
        }

        private float[] getLastY() {
            return this.lastY;
        }

        private boolean[] getValid() {
            return this.valid;
        }

        private Track[] getLastTrack() {
            return this.lastTrack;
        }
    }

    public static Layer getPrints() {
        return prints;
    }

    public static Layer getTires() {
        return tires;
    }

    public static long chunkKey(int chunkX, int chunkY) {
        return (long) chunkX << 32 | chunkY & 0xFFFFFFFFL;
    }

    private static long chunkKey(float x, float y) {
        return chunkKey(PZMath.fastfloor(x / 8.0f), PZMath.fastfloor(y / 8.0f));
    }

    private static long cellKey(int cellX, int cellY, int z) {
        return (cellX & 0xFFFFFL) << 28 | (cellY & 0xFFFFFL) << 8 | z + 32 & 0xFFL;
    }

    private static long densityKey(float x, float y, int z) {
        return cellKey(PZMath.fastfloor(x / DENSITY_CELL), PZMath.fastfloor(y / DENSITY_CELL), z);
    }

    private static boolean isActive() {
        return !GameServer.server && IsoWorld.instance.currentCell != null && IsoWorld.instance.currentCell.getSnowTarget() > 0;
    }

    private static boolean isNearLocalPlayer(IsoGameCharacter chr, float radius) {
        for (int i = 0; i < IsoPlayer.numPlayers; i++) {
            IsoPlayer player = IsoPlayer.getPlayer(i);
            if (player != null && PZMath.fastfloor(player.getZ()) == PZMath.fastfloor(chr.getZ()) && player.DistToSquared(chr) < radius * radius) {
                return true;
            }
        }
        return false;
    }

    public static void onFootstep(IsoGameCharacter chr) {
        if (!isActive() || chr.getVehicle() != null || chr.isOnFloor() || chr.isDead()) {
            return;
        }

        if (chr.isZombie() && !isNearLocalPlayer(chr, ZOMBIE_RADIUS)) {
            return;
        }

        FootState feet = chr.getSnowTrackFeet();
        feet.leftFoot = !feet.leftFoot;
        float dx = chr.getForwardDirectionX();
        float dy = chr.getForwardDirectionY();
        float side = feet.leftFoot ? -FOOT_SIDE : FOOT_SIDE;
        float x = chr.getX() + dx * FOOT_FORWARD - dy * side;
        float y = chr.getY() + dy * FOOT_FORWARD + dx * side;
        int z = PZMath.fastfloor(chr.getZ());
        byte type = feet.leftFoot ? FOOT_LEFT : FOOT_RIGHT;
        if (hasSnow(x, y, z)) {
            place(prints, x, y, z, dx, dy, FOOT_LENGTH, FOOT_WIDTH, 1.0f, false, type);
            feet.carry = CARRY_STEPS;
        } else if (feet.carry > 0) {
            if (canCarryOnto(x, y, z)) {
                place(prints, x, y, z, dx, dy, FOOT_LENGTH, FOOT_WIDTH, (float) feet.carry / CARRY_STEPS, true, type);
            }
            feet.carry--;
        }
    }

    public static void onVehicleUpdate(BaseVehicle vehicle, WheelState state) {
        VehicleScript script = vehicle.getScript();
        if (!isActive() || script == null) {
            state.reset();
            return;
        }

        // Before the parked check, so the rest height is known when it lifts off straight up
        int level = PZMath.fastfloor(vehicle.getZ());
        if (state.isAirborne(level, vehicle.getDebugZ() - level)) {
            state.breakPaths();
            return;
        }

        if (!state.updateVehiclePosition(vehicle.getX(), vehicle.getY())) {
            return;
        }

        int wheels = Math.min(script.getWheelCount(), WheelState.MAX_WHEELS);
        Vector3f pos = BaseVehicle.allocVector3f();
        try {
            for (int i = 0; i < wheels; i++) {
                VehicleScript.Wheel wheel = script.getWheel(i);
                vehicle.getWorldPos(wheel.getOffset(), pos);
                float dx = pos.x() - state.getLastX()[i];
                float dy = pos.y() - state.getLastY()[i];
                float distance = PZMath.sqrt(dx * dx + dy * dy);

                if (!state.getValid()[i] || distance > TELEPORT) {
                    state.getLastX()[i] = pos.x();
                    state.getLastY()[i] = pos.y();
                    state.getValid()[i] = true;
                    state.getLastTrack()[i] = null;
                    continue;
                }

                if (distance < TIRE_STEP) {
                    continue;
                }

                float ux = dx / distance;
                float uy = dy / distance;
                float width = Math.min(wheel.width, TIRE_MAX_WIDTH) / RUT_FRACTION;
                while (distance >= TIRE_STEP) {
                    float nx = state.getLastX()[i] + ux * TIRE_STEP;
                    float ny = state.getLastY()[i] + uy * TIRE_STEP;
                    Track segment = placeRut(state, i, (state.getLastX()[i] + nx) * 0.5f, (state.getLastY()[i] + ny) * 0.5f, level, ux, uy, width);
                    join(state.getLastTrack()[i], segment);
                    state.getLastTrack()[i] = segment;
                    state.getLastX()[i] = nx;
                    state.getLastY()[i] = ny;
                    distance -= TIRE_STEP;
                }
            }
        } finally {
            BaseVehicle.releaseVector3f(pos);
        }
    }

    private static boolean hasSnow(float x, float y, int z) {
        int squareX = PZMath.fastfloor(x);
        int squareY = PZMath.fastfloor(y);
        int sides = FBORenderSnow.getInstance().getSnowSides(squareX, squareY, z);
        return FBORenderSnow.getCoverage(sides, x - squareX, y - squareY) >= DENT_COVERAGE;
    }

    private static boolean canCarryOnto(float x, float y, int z) {
        IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(PZMath.fastfloor(x), PZMath.fastfloor(y), z);
        return square != null && square.getProperties().has(IsoFlagType.solidfloor) && !square.getProperties().has(IsoFlagType.water);
    }

    private static Track place(Layer layer, float x, float y, int z, float dirX, float dirY, float length, float width, float strength, boolean carried, byte type) {
        byte variant = (byte) Rand.Next(VARIANTS);
        Track track = new Track(x, y, z, dirX, dirY, length, width, strength, carried, type, variant, layer.clock);
        layer.add(track);
        return track;
    }

    private static Track placeRut(WheelState state, int wheel, float x, float y, int z, float dirX, float dirY, float width) {
        if (hasFreshRut(x, y, z, dirX, dirY)) {
            return null;
        }

        Track segment = null;
        if (hasSnow(x, y, z)) {
            segment = place(tires, x, y, z, dirX, dirY, TIRE_STEP, width, 1.0f, false, TIRE);
            state.carry[wheel] = CARRY_SEGMENTS;
        } else if (state.carry[wheel] > 0) {
            if (canCarryOnto(x, y, z)) {
                segment = place(tires, x, y, z, dirX, dirY, TIRE_STEP, width, (float) state.carry[wheel] / CARRY_SEGMENTS, true, TIRE);
            }
            state.carry[wheel]--;
        }

        if (segment != null) {
            addToPass(state, wheel, segment);
            rutGrid.put(cellKey(PZMath.fastfloor(x / DEDUP_GRID), PZMath.fastfloor(y / DEDUP_GRID), z), segment);
        }

        return segment;
    }

    private static boolean hasFreshRut(float x, float y, int z, float dirX, float dirY) {
        int gridX = PZMath.fastfloor(x / DEDUP_GRID);
        int gridY = PZMath.fastfloor(y / DEDUP_GRID);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                Track rut = rutGrid.get(cellKey(gridX + i, gridY + j, z));
                if (rut == null || !isFresh(rut) || Math.abs(rut.getDirX() * dirX + rut.getDirY() * dirY) < DEDUP_MIN_DOT) {
                    continue;
                }

                float dx = rut.getX() - x;
                float dy = rut.getY() - y;
                if (Math.abs(dx * dirX + dy * dirY) < DEDUP_ALONG && Math.abs(dy * dirX - dx * dirY) < DEDUP_SIDE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isFresh(Track rut) {
        return !rut.isRemoved() && rut.getFadeOutAt() == 0 && tires.getAge(rut) < DEDUP_FRESH;
    }

    // Gives consecutive segments a shared edge halfway between their directions, so curves have no gaps or overlaps
    private static void join(Track previous, Track next) {
        if (previous == null || next == null || previous.isRemoved()) {
            return;
        }

        float dot = previous.getDirX() * next.getDirX() + previous.getDirY() * next.getDirY();
        if (dot < MIN_JOIN_DOT) {
            return;
        }

        float mx = previous.getDirX() + next.getDirX();
        float my = previous.getDirY() + next.getDirY();
        float length = PZMath.sqrt(mx * mx + my * my);
        mx /= length;
        my /= length;
        float stretch = next.getWidth() * 0.5f / (mx * next.getDirX() + my * next.getDirY());
        previous.setFrontAcross(-my * stretch, mx * stretch);
        next.setRearAcross(-my * stretch, mx * stretch);
    }

    // Each density cell keeps DENSITY_PASSES passes; a further one fades out the oldest
    private static void addToPass(WheelState state, int wheel, Track segment) {
        long key = densityKey(segment.getX(), segment.getY(), segment.getZ());
        Pass[] passes = density.get(key);
        if (passes == null) {
            passes = new Pass[DENSITY_PASSES];
            density.put(key, passes);
        }

        for (Pass pass : passes) {
            if (pass != null && pass.owner == state && pass.wheel == wheel && pass.segments.size() < MAX_PASS_SEGMENTS && !pass.isGone()) {
                pass.segments.add(segment);
                return;
            }
        }

        int slot = -1;
        for (int i = 0; i < passes.length && slot < 0; i++) {
            if (passes[i] == null || passes[i].isGone()) {
                slot = i;
            }
        }

        if (slot < 0) {
            slot = 0;
            for (int i = 1; i < passes.length; i++) {
                if (passes[i].createdAt < passes[slot].createdAt) {
                    slot = i;
                }
            }

            passes[slot].fadeOut(System.currentTimeMillis());
        }

        passes[slot] = new Pass(state, wheel, tires.clock);
        passes[slot].segments.add(segment);
    }

    private static void pruneDensity() {
        density.retainEntries((_, passes) -> {
            for (Pass pass : passes) {
                if (pass != null && !pass.isGone()) return true;
            }
            return false;
        });
    }

    public static void update() {
        if (GameServer.server) {
            return;
        }

        IsoCell cell = IsoWorld.instance.getCell();
        if (cell != lastCell) {
            lastCell = cell;
            reset();
        }

        double now = GameTime.getInstance().getWorldAgeHours();
        float hours = lastWorldAgeHours < 0.0 ? 0.0f : (float) Math.max(0.0, now - lastWorldAgeHours);
        lastWorldAgeHours = now;
        if (cell == null || cell.getSnowTarget() <= 0) {
            if (!prints.isEmpty() || !tires.isEmpty()) {
                reset();
            }
            return;
        }

        ClimateManager climateManager = ClimateManager.getInstance();
        float rate = 1.0f + (climateManager.isSnowing() ? climateManager.getPrecipitationIntensity() * SNOWFALL_COVER_RATE : 0.0f);
        prints.advance(hours, rate);
        tires.advance(hours, rate);
        tires.purgeFaded(System.currentTimeMillis());
        if (density.size() > densityPruneAt) {
            pruneDensity();
            densityPruneAt = Math.max(PRUNE_SIZE, density.size() * 2);
        }

        if (rutGrid.size() > rutGridPruneAt) {
            rutGrid.retainEntries((_, rut) -> isFresh(rut));
            rutGridPruneAt = Math.max(PRUNE_SIZE, rutGrid.size() * 2);
        }
    }

    private static void reset() {
        prints.clear();
        tires.clear();
        density.clear();
        densityPruneAt = PRUNE_SIZE;
        rutGrid.clear();
        rutGridPruneAt = PRUNE_SIZE;
        lastWorldAgeHours = -1.0;
    }
}
