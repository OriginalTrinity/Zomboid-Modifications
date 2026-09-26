package zombie.iso;

import org.jetbrains.annotations.Nullable;
import zombie.GameTime;
import zombie.core.Core;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.areas.IsoRoom;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;
import zombie.iso.objects.*;
import zombie.iso.objects.interfaces.BarricadeAble;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.RoomTemperatureManager;
import zombie.iso.weather.dbg.ThermalForecast;
import zombie.network.GameClient;
import zombie.network.PacketTypes;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.RoomThermalDebugPacket;
import zombie.network.packets.RoomThermalSnapshotPacket;

import java.util.*;

public class IsoThermalRoom {

    private static final long REGION_ID_FLAG = 1L << 62;
    private static final int MAX_FLOODFILL_STEPS = 20000;

    private DebugInfo debugInfo;

    private final IsoRoom room;
    private final int x, y, z;
    private final long id;
    private final boolean isPlayerRoom;
    private float currentTemp;
    private float targetTemp;
    private double lastUpdate;
    private float weightSum;
    private float weightedSum;
    private float roofFraction;
    private final ArrayList<IsoGridSquare> squares;
    private final ArrayList<RoomOpening> openings;
    private final ArrayList<StairLink> stairLinks;
    private final HashSet<Long> squareHashes;

    public record StairLink(IsoGridSquare bottomLanding, IsoGridSquare topLanding) {
    }

    public record RoomOpening(IsoGridSquare square, IsoObject door, IsoWindow window, IsoRoom neighborRoom,
                              IsoWorldRegion neighborRegion, IsoGridSquare neighborSquare, boolean north) {
        public boolean isInterRoom() {
            return this.neighborRoom != null || this.neighborRegion != null;
        }

        public boolean isOutdoorWindow() {
            return this.window != null && !this.isInterRoom();
        }

        public String type() {
            return this.window != null ? "Window" : this.door != null ? "Door" : "Breach";
        }
    }

    public IsoThermalRoom(IsoWorldRegion region, IsoGridSquare seed) {
        this.room = null;
        this.isPlayerRoom = true;
        this.squares = this.findAllSquares(region, seed);
        this.openings = new ArrayList<>();
        this.stairLinks = new ArrayList<>();
        this.squareHashes = new HashSet<>();

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (IsoGridSquare sq : this.squares) {
            if (sq.getX() < minX) minX = sq.getX();
            if (sq.getY() < minY) minY = sq.getY();
        }
        this.x = minX == Integer.MAX_VALUE ? seed.getX() : minX;
        this.y = minY == Integer.MAX_VALUE ? seed.getY() : minY;
        this.z = seed.getZ();
        this.id = packRegionId(this.x, this.y, this.z);
        this.currentTemp = IsoWorld.instance.isHydroPowerOn() ? 22.0f : ClimateManager.getInstance().getTemperature();
        this.targetTemp = Float.NaN;
        this.lastUpdate = GameTime.getInstance().getWorldAgeHours();
        this.debugInfo = Core.debug ? new DebugInfo() : null;

        this.rebuildSquareHashes();
        this.scanPerimeter();
    }

    public IsoThermalRoom(IsoRoom room) {
        this.room = room;
        this.isPlayerRoom = false;
        this.squares = new ArrayList<>(room.getSquares());
        this.x = room.getRoomDef().getX();
        this.y = room.getRoomDef().getY();
        this.z = room.getRoomDef().getZ();
        this.id = room.getRoomDef().getID();
        this.openings = new ArrayList<>();
        this.stairLinks = new ArrayList<>();
        this.squareHashes = null;
        this.currentTemp = IsoWorld.instance.isHydroPowerOn() ? 22.0f : ClimateManager.getInstance().getTemperature();
        this.targetTemp = Float.NaN;
        this.lastUpdate = GameTime.getInstance().getWorldAgeHours();
        this.debugInfo = Core.debug ? new DebugInfo() : null;

        this.scanPerimeter();
    }

    /**
     * Client-side display cache only - no live geometry, never simulated locally. Its {@link DebugInfo} is filled from the server.
     */
    public IsoThermalRoom(long id, int x, int y, int z, boolean isPlayerRoom, float currentTemp, Set<Long> squareHashes) {
        this.room = null;
        this.isPlayerRoom = isPlayerRoom;
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
        this.currentTemp = currentTemp;
        this.lastUpdate = GameTime.getInstance().getWorldAgeHours();
        this.squares = null;
        this.openings = null;
        this.stairLinks = null;
        this.squareHashes = isPlayerRoom && squareHashes != null ? new HashSet<>(squareHashes) : null;
        this.debugInfo = Core.debug ? new DebugInfo() : null;
    }

    private static long packRegionId(int x, int y, int z) {
        return REGION_ID_FLAG | packCoordinates(x, y, z);
    }

    public static long packCoordinates(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 24) | ((long) (y & 0xFFFFF) << 4) | (long) (z & 0xF);
    }

    public static int unpackX(long packed) {
        return (int) ((packed >> 24) & 0xFFFFF);
    }

    public static int unpackY(long packed) {
        return (int) ((packed >> 4) & 0xFFFFF);
    }

    public boolean occupiesChunk(int wx, int wy) {
        for (long hash : this.squareHashes) {
            if (unpackX(hash) / 8 == wx && unpackY(hash) / 8 == wy) return true;
        }
        return false;
    }

    private boolean rebuildSquareHashes() {
        if (this.squareHashes == null) return false;
        HashSet<Long> previous = new HashSet<>(this.squareHashes);
        this.squareHashes.clear();
        for (IsoGridSquare sq : this.squares) {
            this.squareHashes.add(packCoordinates(sq.getX(), sq.getY(), sq.getZ()));
        }
        return !previous.equals(this.squareHashes);
    }

    private boolean belongsToMe(IsoGridSquare sq) {
        if (sq == null) return false;
        return this.isPlayerRoom ? this.containsSquare(sq) : sq.getRoom() == this.room;
    }

    public static IsoGridSquare getAdjacentSquare(IsoGridSquare sq, int dx, int dy) {
        if (sq == null) return null;
        IsoCell cell = IsoWorld.instance.getCell();
        return cell == null ? null : cell.getGridSquare(sq.getX() + dx, sq.getY() + dy, sq.getZ());
    }

    private ArrayList<IsoGridSquare> findAllSquares(IsoWorldRegion region, IsoGridSquare seed) {
        ArrayList<IsoGridSquare> result = new ArrayList<>();
        if (seed == null || region == null) return result;

        HashSet<IsoGridSquare> visited = new HashSet<>();
        ArrayDeque<IsoGridSquare> queue = new ArrayDeque<>();
        queue.add(seed);
        visited.add(seed);

        int steps = 0;
        while (!queue.isEmpty() && steps < MAX_FLOODFILL_STEPS) {
            IsoGridSquare cur = queue.poll();
            result.add(cur);
            for (IsoGridSquare n : new IsoGridSquare[]{
                    getAdjacentSquare(cur, 0, -1), getAdjacentSquare(cur, 0, 1),
                    getAdjacentSquare(cur, -1, 0), getAdjacentSquare(cur, 1, 0)}) {
                if (n != null && RoomTemperatureManager.getRegionOfSquare(n) == region && visited.add(n)) {
                    queue.add(n);
                }
            }
            steps++;
        }
        return result;
    }

    private float calculateRoofFraction() {
        if (this.squares.isEmpty()) return 0.0f;
        IsoCell cell = IsoWorld.instance.getCell();
        int exposed = 0;
        for (IsoGridSquare sq : this.squares) {
            IsoGridSquare above = cell.getGridSquare(sq.getX(), sq.getY(), sq.getZ() + 1);
            boolean covered = above != null && (RoomTemperatureManager.getMappedRoom(above) != null || RoomTemperatureManager.getRegionOfSquare(above) != null);
            if (!covered) exposed++;
        }
        return (float) exposed / this.squares.size();
    }

    public float getFloorOutdoorMultiplier() {
        if (this.z >= 0) return 1.0f;
        return Math.max(1.0f - RoomTemperatureManager.ThermalConfig.BASEMENT_OUTDOOR_FACTOR * -this.z, RoomTemperatureManager.ThermalConfig.MIN_FLOOR_OUTDOOR_MULTIPLIER);
    }

    public void scanPerimeter() {
        this.openings.clear();
        this.stairLinks.clear();
        this.roofFraction = this.calculateRoofFraction();
        for (IsoGridSquare sq : this.squares) {
            if (!this.belongsToMe(sq)) continue;

            // North/west openings live on this square itself.
            this.scanEdge(sq, this.getAdjacentSquare(sq, 0, -1), true);
            this.scanEdge(sq, this.getAdjacentSquare(sq, -1, 0), false);
            // South/east openings live on the neighbor's own north/west edge.
            IsoGridSquare s = this.getAdjacentSquare(sq, 0, 1);
            if (s != null) this.scanEdge(s, s, true);
            IsoGridSquare e = this.getAdjacentSquare(sq, 1, 0);
            if (e != null) this.scanEdge(e, e, false);
        }

        for (IsoGridSquare sq : this.squares) {
            StairLink link = this.stairLinkFrom(sq);
            if (link != null && !this.stairLinks.contains(link)) this.stairLinks.add(link);
        }
    }

    private void scanEdge(IsoGridSquare hostSquare, IsoGridSquare otherSideSquare, boolean north) {
        if (hostSquare == null) return;

        IsoObject door = this.getDoorOnEdge(hostSquare, north);
        IsoWindow window = door == null ? this.getWindowOnEdge(hostSquare, north) : null;

        if (door == null && window == null && this.hasSolidWall(hostSquare, north)) {
            return; // solid wall here, not an opening
        }
        if (this.belongsToMe(otherSideSquare)) return;

        IsoWorldRegion otherRegion = RoomTemperatureManager.getRegionOfSquare(otherSideSquare);
        IsoRoom neighborRoom = otherRegion == null ? RoomTemperatureManager.getMappedRoom(otherSideSquare) : null;
        this.openings.add(new RoomOpening(hostSquare, door, window, neighborRoom, otherRegion, otherSideSquare, north));
    }

    private boolean hasSolidWall(IsoGridSquare sq, boolean north) {
        sq.RecalcPropertiesIfNeeded();

        boolean collides = north
                ? sq.getProperties().has(IsoFlagType.collideN)
                : sq.getProperties().has(IsoFlagType.collideW);
        if (!collides) return false;

        // Hoppable edges (wall frames, railings, low walls) carry wall flags but are open to the air
        if (north ? sq.getProperties().has(IsoFlagType.HoppableN) : sq.getProperties().has(IsoFlagType.HoppableW))
            return false;

        if (sq.getProperties().has(IsoFlagType.WallNW) || sq.getProperties().has(IsoFlagType.WallSE)) {
            return true;
        }
        return north
                ? sq.getProperties().has(IsoFlagType.WallN) || sq.getProperties().has(IsoFlagType.WallNTrans)
                : sq.getProperties().has(IsoFlagType.WallW) || sq.getProperties().has(IsoFlagType.WallWTrans);
    }

    private IsoObject getDoorOnEdge(IsoGridSquare sq, boolean north) {
        if (sq == null) return null;
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject obj = sq.getObjects().get(i);
            if (obj instanceof IsoDoor door) {
                if (door.getNorth() == north) return door;
            } else if (obj instanceof IsoThumpable thumpable && thumpable.isDoor()) {
                if (thumpable.getNorth() == north) return thumpable;
            }
        }
        return null;
    }

    private IsoWindow getWindowOnEdge(IsoGridSquare sq, boolean north) {
        if (sq == null) return null;
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject obj = sq.getObjects().get(i);
            if (obj instanceof IsoWindow window && window.getNorth() == north) {
                return window;
            }
        }
        return null;
    }

    private boolean hasStairs(IsoGridSquare sq, boolean north) {
        if (sq == null) return false;
        for (int i = 0; i < sq.getObjects().size(); i++) {
            IsoObject obj = sq.getObjects().get(i);
            if (north ? obj.isStairsNorth() : obj.isStairsWest()) return true;
        }
        return false;
    }

    private IsoGridSquare findBottomStairSquare(IsoGridSquare sq, boolean north) {
        IsoGridSquare cur = sq;
        for (int steps = 0; steps < 4; steps++) {
            IsoGridSquare next = north ? this.getAdjacentSquare(cur, 0, 1) : this.getAdjacentSquare(cur, 1, 0);
            if (next != null && this.hasStairs(next, north)) {
                cur = next;
            } else {
                break;
            }
        }
        return cur;
    }

    private StairLink stairLinkFrom(IsoGridSquare sq) {
        if (sq == null) return null;
        StairLink down = this.stairLinkGoingDown(sq);
        return down != null ? down : this.stairLinkGoingUp(sq);
    }

    private StairLink stairLinkGoingUp(IsoGridSquare sq) {
        boolean north = this.hasStairs(sq, true);
        boolean west = !north && this.hasStairs(sq, false);
        if (!north && !west) return null;

        IsoGridSquare bottomBase = this.findBottomStairSquare(sq, north);
        if (bottomBase == null) return null;

        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return null;

        int x = bottomBase.getX(), y = bottomBase.getY(), z = bottomBase.getZ();
        IsoGridSquare bottomLanding, topLanding;
        if (north) {
            bottomLanding = bottomBase.getS() != null ? bottomBase.getS() : cell.getGridSquare(x, y + 1, z);
            topLanding = cell.getGridSquare(x, y - 3, z + 1);
        } else {
            bottomLanding = bottomBase.getE() != null ? bottomBase.getE() : cell.getGridSquare(x + 1, y, z);
            topLanding = cell.getGridSquare(x - 3, y, z + 1);
        }
        if (bottomLanding == null || topLanding == null) return null;
        return new StairLink(bottomLanding, topLanding);
    }

    private StairLink stairLinkGoingDown(IsoGridSquare sq) {
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return null;

        IsoGridSquare northBase = cell.getGridSquare(sq.getX(), sq.getY() + 3, sq.getZ() - 1);
        if (this.isValidStairBase(northBase, true)) {
            IsoGridSquare bottomLanding = northBase.getS() != null ? northBase.getS() : cell.getGridSquare(northBase.getX(), northBase.getY() + 1, northBase.getZ());
            if (bottomLanding != null) return new StairLink(bottomLanding, sq);
        }

        IsoGridSquare westBase = cell.getGridSquare(sq.getX() + 3, sq.getY(), sq.getZ() - 1);
        if (this.isValidStairBase(westBase, false)) {
            IsoGridSquare bottomLanding = westBase.getE() != null ? westBase.getE() : cell.getGridSquare(westBase.getX() + 1, westBase.getY(), westBase.getZ());
            if (bottomLanding != null) return new StairLink(bottomLanding, sq);
        }
        return null;
    }

    private boolean isValidStairBase(IsoGridSquare sq, boolean north) {
        if (!this.hasStairs(sq, north)) return false;
        IsoGridSquare further = north ? getAdjacentSquare(sq, 0, 1) : getAdjacentSquare(sq, 1, 0);
        return !this.hasStairs(further, north);
    }

    public Optional<IsoThermalRoom> getStairConnectedRoom(StairLink stairLink) {
        Optional<IsoThermalRoom> optional = RoomTemperatureManager.getInstance().getSimulatedRoom(stairLink.bottomLanding());
        if (optional.isPresent() && optional.get() != this) return optional;
        optional = RoomTemperatureManager.getInstance().getSimulatedRoom(stairLink.topLanding());
        return (optional.isPresent() && optional.get() != this) ? optional : Optional.empty();
    }

    public List<IsoGridSquare> recomputeFloodfill() {
        if (this.squares.isEmpty()) return List.of();
        IsoWorldRegion current = RoomTemperatureManager.getRegionOfSquare(this.squares.get(0));
        return current == null ? List.of() : this.findAllSquares(current, this.squares.get(0));
    }

    public void refreshSquares() {
        if (this.room == null) return;
        this.squares.clear();
        this.squares.addAll(this.room.getSquares());
    }

    public boolean applyRescannedSquares(List<IsoGridSquare> newSquares) {
        this.squares.clear();
        this.squares.addAll(newSquares);
        boolean changed = this.rebuildSquareHashes();
        this.scanPerimeter();
        return changed;
    }

    public List<IsoHeatSource> getHeatSources() {
        if (this.room != null) {
            return IsoWorld.instance.getCell().getHeatSources().stream()
                    .filter(isoHeatSource -> room.isInside(isoHeatSource.getX(), isoHeatSource.getY(), isoHeatSource.getZ())).toList();
        } else {
            return IsoWorld.instance.getCell().getHeatSources().stream()
                    .filter(heatSource -> this.containsSquare(heatSource.getX(), heatSource.getY(), heatSource.getZ())).toList();
        }
    }

    private float calculateWindowCoefficient(IsoWindow window) {
        float coefficient = (window.isSmashed() || window.IsOpen()) ? RoomTemperatureManager.ThermalConfig.WINDOW_OPEN_COEFFICIENT : RoomTemperatureManager.ThermalConfig.WINDOW_CLOSED_COEFFICIENT;
        coefficient *= this.calculateBarricadeInsulation(window);
        IsoCurtain curtain = window.HasCurtains();
        if (curtain != null && !curtain.isCurtainOpen())
            coefficient *= RoomTemperatureManager.ThermalConfig.WINDOW_CURTAIN_MULTIPLIER;
        return coefficient;
    }

    private float calculateDoorCoefficient(IsoObject object) {
        if (object == null) return RoomTemperatureManager.ThermalConfig.BREACH_COEFFICIENT;
        boolean destroyed, open;
        if (object instanceof IsoDoor door) {
            destroyed = door.isDestroyed();
            open = door.isOpen();
        } else if (object instanceof IsoThumpable thumpable) {
            destroyed = thumpable.isDestroyed();
            open = thumpable.open;
        } else {
            return 0.0f;
        }
        if (destroyed) return RoomTemperatureManager.ThermalConfig.BREACH_COEFFICIENT;
        return open ? RoomTemperatureManager.ThermalConfig.DOOR_OPEN_COEFFICIENT : RoomTemperatureManager.ThermalConfig.DOOR_CLOSED_COEFFICIENT;
    }

    public float effectiveCoefficient(RoomOpening opening) {
        float coefficient = opening.window != null ? this.calculateWindowCoefficient(opening.window()) : this.calculateDoorCoefficient(opening.door());
        return coefficient * (opening.isInterRoom() ? RoomTemperatureManager.ThermalConfig.INTERROOM_TRANSFER_MULTIPLIER : this.getFloorOutdoorMultiplier());
    }

    public float calculateHeatSourceCoefficient(IsoHeatSource heatSource) {
        return Math.clamp(heatSource.getRadius() * RoomTemperatureManager.ThermalConfig.HEATSOURCE_RADIUS_SCALE, RoomTemperatureManager.ThermalConfig.HEATSOURCE_MIN_COEFFICIENT, RoomTemperatureManager.ThermalConfig.HEATSOURCE_MAX_COEFFICIENT);
    }

    private float calculateBarricadeInsulation(BarricadeAble barricadeAble) {
        float insulation = 1.0f;
        IsoBarricade same = barricadeAble.getBarricadeOnSameSquare();
        if (same != null) {
            insulation *= same.getLightTransmission();
        }
        IsoBarricade opposite = barricadeAble.getBarricadeOnOppositeSquare();
        if (opposite != null) {
            insulation *= opposite.getLightTransmission();
        }
        insulation = 1.0f - (1.0f - insulation) * RoomTemperatureManager.ThermalConfig.BARRICADE_INSULATION_MULTIPLIER;
        return Math.max(insulation, 0.05f);
    }

    public void calculateTargetTemperature() {
        this.calculateTargetTemperature(ClimateManager.getInstance().getTemperature(), RoomTemperatureManager.getCurrentSunStrength());
    }

    public void calculateTargetTemperature(float outsideTemp, float sunStrength) {
        float[] eval = this.evaluateTargetTemperature(outsideTemp, sunStrength, IsoThermalRoom::getCurrentTemperature, this.debugInfo != null ? this.debugInfo.contributions : null);
        this.weightSum = eval[0];
        this.weightedSum = eval[1];
        this.targetTemp = eval[2];
    }

    public float[] evaluateTargetTemperature(float outsideTemp, float sunStrength, NeighborTemperature neighborTemp, @Nullable float[] contributions) {
        float effectiveOutsideTemp = outsideTemp - RoomTemperatureManager.ThermalConfig.UPPER_FLOOR_TEMP_DROP * Math.max(this.z, 0); // Upper floor exchange temp
        float solarOutsideTemp = effectiveOutsideTemp + RoomTemperatureManager.ThermalConfig.SOLAR_ROOF_GAIN * sunStrength * this.roofFraction; // Sun exposed roof exchange temp
        float outdoorMultiplier = this.getFloorOutdoorMultiplier();

        float weightSum = RoomTemperatureManager.ThermalConfig.BASE_COEFFICIENT * outdoorMultiplier;
        float weightedSum = weightSum * solarOutsideTemp;

        if (contributions != null) {
            Arrays.fill(contributions, 0f);
            contributions[DebugInfo.CONTRIB_BASE] = weightSum;
        }

        for (RoomOpening opening : this.getOpenings()) {
            float otherTemp;
            if (opening.isInterRoom()) {
                Optional<IsoThermalRoom> neighbor = RoomTemperatureManager.getInstance().resolveNeighborRoom(opening);
                if (neighbor.isEmpty()) continue;
                otherTemp = neighborTemp.get(neighbor.get());
            } else {
                otherTemp = effectiveOutsideTemp;
            }
            float coefficient = this.effectiveCoefficient(opening);
            weightSum += coefficient;
            weightedSum += coefficient * otherTemp;
            if (contributions != null) {
                int category = opening.isOutdoorWindow() ? DebugInfo.CONTRIB_WINDOWS : DebugInfo.CONTRIB_OPENINGS;
                contributions[category] += coefficient;
            }
        }

        for (StairLink stairLink : this.stairLinks) {
            Optional<IsoThermalRoom> otherRoom = this.getStairConnectedRoom(stairLink);
            if (otherRoom.isEmpty()) continue;
            float coefficient = RoomTemperatureManager.ThermalConfig.STAIR_LINK_COEFFICIENT * RoomTemperatureManager.ThermalConfig.INTERROOM_TRANSFER_MULTIPLIER;
            weightSum += coefficient;
            weightedSum += coefficient * neighborTemp.get(otherRoom.get());
            if (contributions != null) contributions[DebugInfo.CONTRIB_STAIRS] += coefficient;
        }

        for (IsoHeatSource heatSource : this.getHeatSources()) {
            float coefficient = this.calculateHeatSourceCoefficient(heatSource);
            weightSum += coefficient;
            weightedSum += coefficient * heatSource.getTemperature();
            if (contributions != null) contributions[DebugInfo.CONTRIB_HEATSOURCES] += coefficient;
        }

        // Basements are pulled towards the ground temperature
        if (this.z < 0) {
            float coefficient = RoomTemperatureManager.ThermalConfig.BASEMENT_GROUND_COEFFICIENT * -this.z;

            weightSum += coefficient;
            weightedSum += coefficient * RoomTemperatureManager.getInstance().getGroundTemperature();
            if (contributions != null) contributions[DebugInfo.CONTRIB_GROUND] += coefficient;
        }

        if (IsoWorld.instance.isHydroPowerOn()) {
            weightSum += RoomTemperatureManager.ThermalConfig.CLIMATE_CONTROL_COEFFICIENT;
            weightedSum += RoomTemperatureManager.ThermalConfig.CLIMATE_CONTROL_COEFFICIENT * 22.0f;

            if (contributions != null) contributions[DebugInfo.CONTRIB_CLIMATE] = RoomTemperatureManager.ThermalConfig.CLIMATE_CONTROL_COEFFICIENT;
        }

        return new float[]{weightSum, weightedSum, weightedSum / weightSum};
    }

    public void applyTemperatureChange() {
        if (Float.isNaN(this.targetTemp)) return;
        double dt = GameTime.getInstance().getWorldAgeHours() - this.lastUpdate;
        if (dt < 0) dt = 0;
        this.stepTemperature(dt);
        this.lastUpdate = GameTime.getInstance().getWorldAgeHours();
    }

    public void stepTemperature(double dt) {
        float delta = computeTempDelta(this.currentTemp, this.targetTemp, this.weightSum, this.squares.size(), dt);
        this.currentTemp += delta;

        if (debugInfo != null) {
            debugInfo.recordStep(delta);
        }
    }

    public static float computeTempDelta(float currentTemp, float targetTemp, float weightSum, int squareCount, double dt) {
        float directionalMultiplier = targetTemp > currentTemp ? RoomTemperatureManager.ThermalConfig.HEATING_RATE_MULTIPLIER : RoomTemperatureManager.ThermalConfig.COOLING_RATE_MULTIPLIER;
        float rate = RoomTemperatureManager.ThermalConfig.TEMP_CHANGE_RATE_MULTIPLIER * directionalMultiplier * weightSum / Math.max(squareCount, 1);
        float rawDelta = (float) ((targetTemp - currentTemp) * (1 - Math.exp(-rate * dt)));
        float maxDelta = (float) (RoomTemperatureManager.ThermalConfig.MAX_TEMP_DELTA * directionalMultiplier * dt);
        return Math.clamp(rawDelta, -maxDelta, maxDelta);
    }

    public boolean containsSquare(IsoGridSquare sq) {
        return sq != null && this.squareHashes != null && this.squareHashes.contains(packCoordinates(sq.getX(), sq.getY(), sq.getZ()));
    }

    public boolean containsSquare(int x, int y, int z) {
        return this.squareHashes != null && this.squareHashes.contains(packCoordinates(x, y, z));
    }

    public long getId() {
        return this.id;
    }

    public int getX() {
        return this.x;
    }

    public int getY() {
        return this.y;
    }

    public int getZ() {
        return this.z;
    }

    public float getCurrentTemperature() {
        return currentTemp;
    }

    public void setCurrentTemperature(float currentTemp) {
        this.currentTemp = currentTemp;
    }

    public float getTargetTemperature() {
        return this.targetTemp;
    }

    public ArrayList<IsoGridSquare> getSquares() {
        return this.squares;
    }

    public ArrayList<RoomOpening> getOpenings() {
        return this.openings;
    }

    public ArrayList<StairLink> getStairLinks() {
        return this.stairLinks;
    }

    public Set<Long> getSquareHashes() {
        return Collections.unmodifiableSet(this.squareHashes);
    }

    public void setSquareHashes(Set<Long> squareHashes) {
        if (this.squareHashes == null || squareHashes == null) return;
        this.squareHashes.clear();
        this.squareHashes.addAll(squareHashes);
    }

    public boolean isPlayerRoom() {
        return this.isPlayerRoom;
    }

    public double getLastUpdate() {
        return this.lastUpdate;
    }

    public void setLastUpdate(double lastUpdate) {
        this.lastUpdate = lastUpdate;
    }

    public DebugInfo getDebugInfo() {
        return this.debugInfo;
    }

    public DebugInfo enableDebugInfo() {
        if (this.debugInfo == null) this.debugInfo = new DebugInfo();
        return this.debugInfo;
    }

    public float getRoofFraction() {
        return this.roofFraction;
    }

    public float getWeightSum() {
        return this.weightSum;
    }

    public float getWeightedSum() {
        return this.weightedSum;
    }

    @FunctionalInterface
    public interface NeighborTemperature {
        float get(IsoThermalRoom room);
    }

    /**
     * Everything the debug UI shows for this room. On the simulating side (SP / server) the getters read the room live;
     * on clients they return what the server last sent via {@link RoomThermalDebugPacket}.
     */
    public class DebugInfo {
        private static final int TEMP_HISTORY_CAPACITY = 180;
        private static final long REFRESH_MS = 1000;
        public static final int CONTRIB_BASE = 0, CONTRIB_WINDOWS = 1, CONTRIB_OPENINGS = 2, CONTRIB_STAIRS = 3, CONTRIB_HEATSOURCES = 4, CONTRIB_GROUND = 5, CONTRIB_CLIMATE = 6,CONTRIB_COUNT = 7;

        public record Opening(int x, int y, boolean north, String type, boolean outdoorWindow, String state, String curtains, String barricades, String facing, float coefficient, float otherTemp) {}
        public record Stair(int bottomX, int bottomY, int bottomZ, int topX, int topY, int topZ, long otherRoomId, float otherTemp) {}
        public record HeatSource(int x, int y, int z, int radius, int temperature, float coefficient) {}

        private float tempChangeDelta;
        private final float[] currentTempHistory;
        private final float[] targetTempHistory;
        private int historyWriteIndex;
        private int historySize;
        private final float[] contributions;
        private ThermalForecast.Result forecast;
        private long forecastMillis; // simulating side: when computed; client: when last requested

        // Client only
        private boolean received;
        private long lastRequestMillis;
        private float outsideTemp, groundTemp, floorOutdoorMultiplier, sunStrength, floorTempDrop, solarGain;
        private Set<Long> tiles;
        private List<Opening> openings;
        private List<Stair> stairs;
        private List<HeatSource> heatSources;

        private DebugInfo() {
            this.currentTempHistory = new float[TEMP_HISTORY_CAPACITY];
            this.targetTempHistory = new float[TEMP_HISTORY_CAPACITY];
            this.contributions = new float[CONTRIB_COUNT];
            this.tiles = Set.of();
            this.openings = List.of();
            this.stairs = List.of();
            this.heatSources = List.of();
        }

        private void recordStep(float delta) {
            this.tempChangeDelta = delta;
            this.currentTempHistory[this.historyWriteIndex] = IsoThermalRoom.this.currentTemp;
            this.targetTempHistory[this.historyWriteIndex] = IsoThermalRoom.this.targetTemp;
            this.historyWriteIndex = (this.historyWriteIndex + 1) % TEMP_HISTORY_CAPACITY;
            if (this.historySize < TEMP_HISTORY_CAPACITY) this.historySize++;
        }

        public void requestRefresh() {
            if (!GameClient.client) return;
            long now = System.currentTimeMillis();
            if (now - this.lastRequestMillis < REFRESH_MS) return;
            this.lastRequestMillis = now;
            INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.RoomThermalDebug, IsoThermalRoom.this.id, RoomThermalDebugPacket.ACTION_VIEW, 0f);
        }

        public boolean hasData() {
            return !GameClient.client || this.received;
        }

        public void runAction(byte action, float value) {
            if (GameClient.client) {
                INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.RoomThermalDebug, IsoThermalRoom.this.id, action, value);
                return;
            }
            switch (action) {
                case RoomThermalDebugPacket.ACTION_RESCAN -> IsoThermalRoom.this.scanPerimeter();
                case RoomThermalDebugPacket.ACTION_SET_TEMP -> IsoThermalRoom.this.setCurrentTemperature(value);
            }
        }

        @Nullable
        public ThermalForecast.Result getForecast() {
            long now = System.currentTimeMillis();
            if (GameClient.client) {
                if (now - this.forecastMillis >= REFRESH_MS) {
                    this.forecastMillis = now;
                    INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.RoomThermalDebug, IsoThermalRoom.this.id, RoomThermalDebugPacket.ACTION_FORECAST, 0f);
                }
                return this.forecast;
            }
            if (this.forecast == null || now - this.forecastMillis >= REFRESH_MS) {
                this.forecast = ThermalForecast.run(IsoThermalRoom.this, ThermalForecast.sampleOutdoorCurve());
                this.forecastMillis = now;
            }
            return this.forecast;
        }

        public float getTempChangeDelta() {
            return this.tempChangeDelta;
        }

        public float[] getCurrentTempHistory() {
            return this.currentTempHistory;
        }

        public float[] getTargetTempHistory() {
            return this.targetTempHistory;
        }

        public int getHistorySize() {
            return this.historySize;
        }

        public int getHistoryWriteIndex() {
            return this.historyWriteIndex;
        }

        public float[] getWeightContributions() {
            return this.contributions;
        }

        public float getOutsideTemp() {
            return GameClient.client ? this.outsideTemp : ClimateManager.getInstance().getTemperature();
        }

        public float getGroundTemp() {
            return GameClient.client ? this.groundTemp : RoomTemperatureManager.getInstance().getGroundTemperature();
        }

        public float getFloorOutdoorMultiplier() {
            return GameClient.client ? this.floorOutdoorMultiplier : IsoThermalRoom.this.getFloorOutdoorMultiplier();
        }

        public float getSunStrength() {
            return GameClient.client ? this.sunStrength : RoomTemperatureManager.getCurrentSunStrength();
        }

        public float getFloorTempDrop() {
            return GameClient.client ? this.floorTempDrop : RoomTemperatureManager.ThermalConfig.UPPER_FLOOR_TEMP_DROP * Math.max(IsoThermalRoom.this.z, 0);
        }

        public float getSolarGain() {
            return GameClient.client ? this.solarGain : RoomTemperatureManager.ThermalConfig.SOLAR_ROOF_GAIN * RoomTemperatureManager.getCurrentSunStrength() * IsoThermalRoom.this.roofFraction;
        }

        public Set<Long> getTiles() {
            if (GameClient.client) return this.tiles;
            if (IsoThermalRoom.this.squareHashes != null) return IsoThermalRoom.this.getSquareHashes();
            Set<Long> result = new HashSet<>();
            for (IsoGridSquare sq : IsoThermalRoom.this.squares) {
                result.add(IsoThermalRoom.packCoordinates(sq.getX(), sq.getY(), sq.getZ()));
            }
            return result;
        }

        public List<Opening> getOpenings() {
            if (GameClient.client) return this.openings;
            float outside = ClimateManager.getInstance().getTemperature();
            List<Opening> result = new ArrayList<>();
            for (RoomOpening opening : IsoThermalRoom.this.openings) {
                String[] states = formatState(opening);
                float otherTemp = !opening.isInterRoom() ? outside : RoomTemperatureManager.getInstance().resolveNeighborRoom(opening)
                        .map(IsoThermalRoom::getCurrentTemperature).orElse(Float.NaN);
                String facing = !opening.isInterRoom() ? "Outside" : opening.neighborRoom != null ? "Room " + opening.neighborRoom.getRoomDef().getID() : "Player region";
                result.add(new Opening(opening.square().getX(), opening.square().getY(), opening.north, opening.type(), opening.isOutdoorWindow(), states[0], states.length > 1 ? states[1] : "", opening.window() != null ? getBarricadeState(opening.window()) : "", facing, IsoThermalRoom.this.effectiveCoefficient(opening), otherTemp));
            }
            return result;
        }

        public List<Stair> getStairs() {
            if (GameClient.client) return this.stairs;
            List<Stair> result = new ArrayList<>();
            for (StairLink link : IsoThermalRoom.this.stairLinks) {
                Optional<IsoThermalRoom> other = IsoThermalRoom.this.getStairConnectedRoom(link);
                IsoGridSquare bottom = link.bottomLanding(), top = link.topLanding();
                result.add(new Stair(bottom.getX(), bottom.getY(), bottom.getZ(), top.getX(), top.getY(), top.getZ(), other.map(IsoThermalRoom::getId).orElse(-1L), other.map(IsoThermalRoom::getCurrentTemperature).orElse(Float.NaN)));
            }
            return result;
        }

        public List<HeatSource> getHeatSources() {
            if (GameClient.client) return this.heatSources;
            return IsoThermalRoom.this.getHeatSources().stream()
                    .map(h -> new HeatSource(h.getX(), h.getY(), h.getZ(), h.getRadius(), h.getTemperature(), IsoThermalRoom.this.calculateHeatSourceCoefficient(h)))
                    .toList();
        }

        public void writeTo(ByteBufferWriter b, boolean includeForecast) {
            IsoThermalRoom room = IsoThermalRoom.this;
            b.putFloat(room.currentTemp);
            b.putFloat(room.targetTemp);
            b.putFloat(room.weightSum);
            b.putFloat(room.weightedSum);
            b.putFloat(room.roofFraction);
            b.putFloat(this.tempChangeDelta);
            b.putFloat(ClimateManager.getInstance().getTemperature());
            b.putFloat(RoomTemperatureManager.getInstance().getGroundTemperature());
            b.putFloat(room.getFloorOutdoorMultiplier());
            b.putFloat(RoomTemperatureManager.getCurrentSunStrength());
            b.putFloat(this.getFloorTempDrop());
            b.putFloat(this.getSolarGain());
            for (float contribution : this.contributions) b.putFloat(contribution);

            b.putInt(this.historySize);
            int start = this.historySize < TEMP_HISTORY_CAPACITY ? 0 : this.historyWriteIndex;
            for (int i = 0; i < this.historySize; i++) {
                int index = (start + i) % TEMP_HISTORY_CAPACITY;
                b.putFloat(this.currentTempHistory[index]);
                b.putFloat(this.targetTempHistory[index]);
            }

            RoomThermalSnapshotPacket.writeTiles(b, this.getTiles());

            List<Opening> openings = this.getOpenings();
            b.putInt(openings.size());
            for (Opening o : openings) {
                b.putInt(o.x());
                b.putInt(o.y());
                b.putBoolean(o.north());
                b.putUTF(o.type());
                b.putBoolean(o.outdoorWindow());
                b.putUTF(o.state());
                b.putUTF(o.curtains());
                b.putUTF(o.barricades());
                b.putUTF(o.facing());
                b.putFloat(o.coefficient());
                b.putFloat(o.otherTemp());
            }

            List<Stair> stairs = this.getStairs();
            b.putInt(stairs.size());
            for (Stair s : stairs) {
                b.putInt(s.bottomX());
                b.putInt(s.bottomY());
                b.putInt(s.bottomZ());
                b.putInt(s.topX());
                b.putInt(s.topY());
                b.putInt(s.topZ());
                b.putLong(s.otherRoomId());
                b.putFloat(s.otherTemp());
            }

            List<HeatSource> heatSources = this.getHeatSources();
            b.putInt(heatSources.size());
            for (HeatSource h : heatSources) {
                b.putInt(h.x());
                b.putInt(h.y());
                b.putInt(h.z());
                b.putInt(h.radius());
                b.putInt(h.temperature());
                b.putFloat(h.coefficient());
            }

            b.putBoolean(includeForecast);
            if (includeForecast) this.getForecast().write(b);
        }

        public void readFrom(ByteBufferReader b) {
            IsoThermalRoom room = IsoThermalRoom.this;
            room.currentTemp = b.getFloat();
            room.targetTemp = b.getFloat();
            room.weightSum = b.getFloat();
            room.weightedSum = b.getFloat();
            room.roofFraction = b.getFloat();
            this.tempChangeDelta = b.getFloat();
            this.outsideTemp = b.getFloat();
            this.groundTemp = b.getFloat();
            this.floorOutdoorMultiplier = b.getFloat();
            this.sunStrength = b.getFloat();
            this.floorTempDrop = b.getFloat();
            this.solarGain = b.getFloat();
            for (int i = 0; i < CONTRIB_COUNT; i++) this.contributions[i] = b.getFloat();

            this.historySize = b.getInt();
            for (int i = 0; i < this.historySize; i++) {
                this.currentTempHistory[i] = b.getFloat();
                this.targetTempHistory[i] = b.getFloat();
            }
            this.historyWriteIndex = this.historySize % TEMP_HISTORY_CAPACITY;

            this.tiles = RoomThermalSnapshotPacket.readTiles(b, room.z);

            List<Opening> openings = new ArrayList<>();
            for (int i = 0, n = b.getInt(); i < n; i++) {
                openings.add(new Opening(b.getInt(), b.getInt(), b.getBoolean(), b.getUTF(), b.getBoolean(),
                        b.getUTF(), b.getUTF(), b.getUTF(), b.getUTF(), b.getFloat(), b.getFloat()));
            }
            List<Stair> stairs = new ArrayList<>();
            for (int i = 0, n = b.getInt(); i < n; i++) {
                stairs.add(new Stair(b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getLong(), b.getFloat()));
            }
            List<HeatSource> heatSources = new ArrayList<>();
            for (int i = 0, n = b.getInt(); i < n; i++) {
                heatSources.add(new HeatSource(b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getFloat()));
            }
            this.openings = openings;
            this.stairs = stairs;
            this.heatSources = heatSources;

            if (b.getBoolean()) this.forecast = ThermalForecast.Result.read(b);
            this.received = true;
        }

        private static String[] formatState(RoomOpening opening) {
            String[] states = new String[1];
            if (opening.window() != null) {
                states = formatState(opening.window());
            } else if (opening.door() instanceof IsoDoor door) {
                states[0] = door.isDestroyed() ? "Destroyed" : (door.isOpen() ? "Open" : "Closed");
            } else if (opening.door() instanceof IsoThumpable thumpable) {
                states[0] = thumpable.isDestroyed() ? "Destroyed" : (thumpable.open ? "Open" : "Closed");
            } else {
                states[0] = "Breached";
            }
            return states;
        }

        private static String[] formatState(IsoWindow window) {
            return new String[]{window.isSmashed() ? "Smashed" : (window.IsOpen() ? "Open" : "Closed"),
                    window.HasCurtains() != null ? (window.HasCurtains().isCurtainOpen() ? "Open" : "Closed") : "None"};
        }

        private static String getBarricadeState(BarricadeAble barricadeAble) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 2; i++) {
                IsoBarricade barricade = i == 0 ? barricadeAble.getBarricadeOnSameSquare() : barricadeAble.getBarricadeOnOppositeSquare();
                if (barricade == null) continue;
                if (barricade.isMetal()) {
                    builder.append("Metal Sheet: 1\n");
                } else if (barricade.isMetalBar()) {
                    builder.append("Metal Bars: 1\n");
                } else {
                    builder.append("Planks: ").append(barricade.getNumPlanks()).append("\n");
                }
            }
            return builder.toString();
        }

    }
}
