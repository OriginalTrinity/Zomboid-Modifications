package zombie.iso;

import zombie.GameTime;
import zombie.core.Core;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.areas.IsoRoom;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;
import zombie.iso.objects.*;
import zombie.iso.objects.interfaces.BarricadeAble;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.RoomTemperatureManager;

import java.util.*;

public class IsoThermalRoom {

    private static final long REGION_ID_FLAG = 1L << 62;
    private static final int MAX_FLOODFILL_STEPS = 20000;

    private final DebugInfo debugInfo;

    private final IsoRoom room;
    private final int x, y, z;
    private final long id;
    private final boolean isPlayerRoom;
    private float currentTemp;
    private float targetTemp;
    private double lastUpdate;
    private float weightSum;
    private float roofFraction;
    private final ArrayList<IsoGridSquare> squares;
    private final ArrayList<IsoWindow> windows;
    private final ArrayList<RoomOpening> openings;
    private final ArrayList<StairLink> stairLinks;
    private final HashSet<Long> squareHashes;

    public record StairLink(IsoGridSquare bottomLanding, IsoGridSquare topLanding) {
    }

    public record RoomOpening(IsoGridSquare square, IsoObject door, IsoWindow window, IsoRoom neighborRoom,
                              IsoWorldRegion neighborRegion, IsoGridSquare neighborSquare, boolean north) {
    }

    public IsoThermalRoom(IsoWorldRegion region, IsoGridSquare seed) {
        this.room = null;
        this.isPlayerRoom = true;
        this.squares = this.findAllSquares(region, seed);
        this.windows = new ArrayList<>();
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
        this.targetTemp = Float.MIN_VALUE;
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
        this.windows = new ArrayList<>();
        this.openings = new ArrayList<>();
        this.stairLinks = new ArrayList<>();
        this.squareHashes = null;
        this.currentTemp = IsoWorld.instance.isHydroPowerOn() ? 22.0f : ClimateManager.getInstance().getTemperature();
        this.targetTemp = Float.MIN_VALUE;
        this.lastUpdate = GameTime.getInstance().getWorldAgeHours();
        this.debugInfo = Core.debug ? new DebugInfo() : null;

        this.scanPerimeter();
    }

    /** Client-side display cache only - no live geometry, never simulated locally (see {@link #calculateTargetTemperature()}'s callers). */
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
        this.windows = null;
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
                    this.getAdjacentSquare(cur, 0, -1), this.getAdjacentSquare(cur, 0, 1),
                    this.getAdjacentSquare(cur, -1, 0), this.getAdjacentSquare(cur, 1, 0)}) {
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
        this.windows.clear();
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
        if (debugInfo != null) debugInfo.updateGeometry();
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

        if (window != null && otherRegion == null && neighborRoom == null) {
            this.windows.add(window); // purely outdoor-facing
        }
    }

    private boolean hasSolidWall(IsoGridSquare sq, boolean north) {
        sq.RecalcPropertiesIfNeeded();

        boolean collides = north
                ? sq.getProperties().has(IsoFlagType.collideN)
                : sq.getProperties().has(IsoFlagType.collideW);
        if (!collides) return false;

        // Hoppable edges (wall frames, railings, low walls) carry wall flags but are open to the air
        if (north ? sq.getProperties().has(IsoFlagType.HoppableN) : sq.getProperties().has(IsoFlagType.HoppableW)) return false;

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

    private boolean hasStairsNorth(IsoGridSquare sq) {
        if (sq == null) return false;
        for (int i = 0; i < sq.getObjects().size(); i++) {
            if (sq.getObjects().get(i).isStairsNorth()) return true;
        }
        return false;
    }

    private boolean hasStairsWest(IsoGridSquare sq) {
        if (sq == null) return false;
        for (int i = 0; i < sq.getObjects().size(); i++) {
            if (sq.getObjects().get(i).isStairsWest()) return true;
        }
        return false;
    }

    private IsoGridSquare findBottomStairSquare(IsoGridSquare sq, boolean north) {
        IsoGridSquare cur = sq;
        for (int steps = 0; steps < 4; steps++) {
            IsoGridSquare next = north ? this.getAdjacentSquare(cur, 0, 1) : this.getAdjacentSquare(cur, 1, 0);
            if (next != null && (north ? this.hasStairsNorth(next) : this.hasStairsWest(next))) {
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
        boolean north = this.hasStairsNorth(sq);
        boolean west = !north && this.hasStairsWest(sq);
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
        if (sq == null || (north ? !this.hasStairsNorth(sq) : !this.hasStairsWest(sq))) return false;
        IsoGridSquare further = north ? getAdjacentSquare(sq, 0, 1) : getAdjacentSquare(sq, 1, 0);
        return further == null || !(north ? this.hasStairsNorth(further) : this.hasStairsWest(further));
    }

    private Optional<IsoThermalRoom> getStairConnectedRoom(StairLink stairLink) {
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
                    .filter(isoHeatSource -> this.squares.stream()
                            .anyMatch(sq -> sq.getX() == isoHeatSource.getX() && sq.getY() == isoHeatSource.getY() && sq.getZ() == isoHeatSource.getZ())).toList();
        }
    }

    private float calculateWindowCoefficient(IsoWindow window) {
        float coefficient = (window.isSmashed() || window.IsOpen()) ? RoomTemperatureManager.ThermalConfig.WINDOW_OPEN_COEFFICIENT : RoomTemperatureManager.ThermalConfig.WINDOW_CLOSED_COEFFICIENT;
        coefficient *= this.calculateBarricadeInsulation(window);
        IsoCurtain curtain = window.HasCurtains();
        if (curtain != null && !curtain.isCurtainOpen()) coefficient *= RoomTemperatureManager.ThermalConfig.WINDOW_CURTAIN_MULTIPLIER;
        return coefficient;
    }

    private float calculateDoorCoefficient(IsoObject object) {
        float coefficient = 0.0f;
        if (object != null) {
            if (object instanceof IsoDoor door) {
                if (door.isDestroyed()) {
                    coefficient += RoomTemperatureManager.ThermalConfig.BREACH_COEFFICIENT;
                } else if (door.isOpen()) {
                    coefficient += RoomTemperatureManager.ThermalConfig.DOOR_OPEN_COEFFICIENT;
                } else {
                    coefficient += RoomTemperatureManager.ThermalConfig.DOOR_CLOSED_COEFFICIENT;
                }
            } else if (object instanceof IsoThumpable thumpable) {
                if (thumpable.isDestroyed()) {
                    coefficient += RoomTemperatureManager.ThermalConfig.BREACH_COEFFICIENT;
                } else if (thumpable.open) {
                    coefficient += RoomTemperatureManager.ThermalConfig.DOOR_OPEN_COEFFICIENT;
                } else {
                    coefficient += RoomTemperatureManager.ThermalConfig.DOOR_CLOSED_COEFFICIENT;
                }
            }
        } else {
            // Breached
            coefficient += RoomTemperatureManager.ThermalConfig.BREACH_COEFFICIENT;
        }
        return coefficient;
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
        float[] eval = this.evaluateTargetTemperature(outsideTemp, sunStrength, IsoThermalRoom::getCurrentTemperature);
        this.weightSum = eval[0];
        this.targetTemp = eval[2];

        if (debugInfo != null) {
            debugInfo.updateTargetTemp();
            debugInfo.setWeightSum(eval[0]);
            debugInfo.setWeightedSum(eval[1]);
        }
    }

    public float[] evaluateTargetTemperature(float outsideTemp, float sunStrength, NeighborTemperature neighborTemp) {
        float effectiveOutsideTemp = outsideTemp - RoomTemperatureManager.ThermalConfig.UPPER_FLOOR_TEMP_DROP * Math.max(this.z, 0); // Upper floor exchange temp
        float solarOutsideTemp = effectiveOutsideTemp + RoomTemperatureManager.ThermalConfig.SOLAR_ROOF_GAIN * sunStrength * this.roofFraction; // Sun exposed roof exchange temp
        float outdoorMultiplier = this.getFloorOutdoorMultiplier();

        float weightSum = RoomTemperatureManager.ThermalConfig.BASE_COEFFICIENT * outdoorMultiplier;
        float weightedSum = weightSum * solarOutsideTemp;

        if (this.debugInfo != null) {
            Arrays.fill(debugInfo.contributions, 0f);
            debugInfo.getWeightContributions()[DebugInfo.CONTRIB_BASE] = weightSum;
        }

        for (IsoWindow window : this.getWindows()) {
            float coefficient = this.calculateWindowCoefficient(window) * outdoorMultiplier;
            weightSum += coefficient;
            weightedSum += coefficient * effectiveOutsideTemp;
            if (this.debugInfo != null) debugInfo.getWeightContributions()[DebugInfo.CONTRIB_WINDOWS] += coefficient;
        }

        for (RoomOpening opening : this.getOpenings()) {
            if (opening.window() != null && opening.neighborRoom() == null && opening.neighborRegion() == null) continue;
            boolean isInterRoom = opening.neighborRoom() != null || opening.neighborRegion() != null;
            float otherTemp;
            if (isInterRoom) {
                Optional<IsoThermalRoom> neighbor = RoomTemperatureManager.getInstance().resolveNeighborRoom(opening);
                if (neighbor.isEmpty()) continue;
                otherTemp = neighborTemp.get(neighbor.get());
            } else {
                otherTemp = effectiveOutsideTemp;
            }
            float coefficient = opening.window() != null
                    ? this.calculateWindowCoefficient(opening.window())
                    : this.calculateDoorCoefficient(opening.door());
            if (isInterRoom) {
                coefficient *= RoomTemperatureManager.ThermalConfig.INTERROOM_TRANSFER_MULTIPLIER;
            } else {
                coefficient *= outdoorMultiplier;
            }
            weightSum += coefficient;
            weightedSum += coefficient * otherTemp;
            if (this.debugInfo != null) debugInfo.getWeightContributions()[DebugInfo.CONTRIB_OPENINGS] += coefficient;
        }

        for (StairLink stairLink : this.stairLinks) {
            Optional<IsoThermalRoom> otherRoom = this.getStairConnectedRoom(stairLink);
            if (otherRoom.isEmpty()) continue;
            float coefficient = RoomTemperatureManager.ThermalConfig.STAIR_LINK_COEFFICIENT * RoomTemperatureManager.ThermalConfig.INTERROOM_TRANSFER_MULTIPLIER;
            weightSum += coefficient;
            weightedSum += coefficient * neighborTemp.get(otherRoom.get());
            if (this.debugInfo != null) debugInfo.getWeightContributions()[DebugInfo.CONTRIB_STAIRS] += coefficient;
        }

        for (IsoHeatSource heatSource : this.getHeatSources()) {
            float coefficient = Math.clamp(heatSource.getRadius() * RoomTemperatureManager.ThermalConfig.HEATSOURCE_RADIUS_SCALE, RoomTemperatureManager.ThermalConfig.HEATSOURCE_MIN_COEFFICIENT, RoomTemperatureManager.ThermalConfig.HEATSOURCE_MAX_COEFFICIENT);
            weightSum += coefficient;
            weightedSum += coefficient * heatSource.getTemperature();
            if (this.debugInfo != null) debugInfo.getWeightContributions()[DebugInfo.CONTRIB_HEATSOURCES] += coefficient;
        }

        // Basements are pulled towards the ground temperature
        if (this.z < 0) {
            float coefficient = RoomTemperatureManager.ThermalConfig.BASEMENT_GROUND_COEFFICIENT * -this.z;

            weightSum += coefficient;
            weightedSum += coefficient * RoomTemperatureManager.getInstance().getGroundTemperature();
            if (this.debugInfo != null) debugInfo.getWeightContributions()[DebugInfo.CONTRIB_GROUND] += coefficient;
        }

        if (IsoWorld.instance.isHydroPowerOn()) {
            weightSum += RoomTemperatureManager.ThermalConfig.CLIMATE_CONTROL_COEFFICIENT;
            weightedSum += RoomTemperatureManager.ThermalConfig.CLIMATE_CONTROL_COEFFICIENT * 22.0f;
        }

        return new float[]{weightSum, weightedSum, weightedSum / weightSum};
    }

    public void applyTemperatureChange() {
        if (this.targetTemp == Float.MIN_VALUE) return;
        double dt = GameTime.getInstance().getWorldAgeHours() - this.lastUpdate;
        if (dt < 0) dt = 0;
        this.stepTemperature(dt, this.getSquares().size());
        this.lastUpdate = GameTime.getInstance().getWorldAgeHours();
    }

    public void stepTemperature(double dt, int squareCount) {
        float delta = computeTempDelta(this.currentTemp, this.targetTemp, this.weightSum, squareCount, dt);
        this.currentTemp += delta;

        if (debugInfo != null) {
            debugInfo.updateCurrentTemp();
            debugInfo.setTempChangeDelta(delta);
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

    public ArrayList<IsoWindow> getWindows() {
        return this.windows;
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

    public float getRoofFraction() {
        return this.roofFraction;
    }

    @FunctionalInterface
    public interface NeighborTemperature {
        float get(IsoThermalRoom room);
    }

    public class DebugInfo {
        private static final int TEMP_HISTORY_CAPACITY = 180;
        public static final int CONTRIB_BASE = 0, CONTRIB_WINDOWS = 1, CONTRIB_OPENINGS = 2, CONTRIB_STAIRS = 3, CONTRIB_HEATSOURCES = 4, CONTRIB_GROUND = 5, CONTRIB_COUNT = 6;

        private final long id;
        private final int x,y,z;
        private final boolean isPlayerRoom;
        private final boolean hasLiveGeometry;
        private float currentTemp;
        private float targetTemp;
        private List<IsoGridSquare> squares;
        private List<IsoWindow> windows;
        private List<RoomOpening> openings;
        private List<StairLink> stairLinks;
        private float weightSum;
        private float weightedSum;
        private float tempChangeDelta;
        private final float[] currentTempHistory;
        private final float[] targetTempHistory;
        private int historyWriteIndex;
        private int historySize;
        private final Float[] contributions;

        private DebugInfo() {
            this.id = IsoThermalRoom.this.id;
            this.x = IsoThermalRoom.this.x;
            this.y = IsoThermalRoom.this.y;
            this.z = IsoThermalRoom.this.z;
            this.isPlayerRoom = IsoThermalRoom.this.isPlayerRoom;
            this.hasLiveGeometry = IsoThermalRoom.this.squares != null;
            this.currentTemp = IsoThermalRoom.this.currentTemp;
            this.targetTemp = IsoThermalRoom.this.targetTemp;
            this.squares = this.hasLiveGeometry ? Collections.unmodifiableList(IsoThermalRoom.this.squares) : Collections.emptyList();
            this.windows = this.hasLiveGeometry ? Collections.unmodifiableList(IsoThermalRoom.this.windows) : Collections.emptyList();
            this.openings = this.hasLiveGeometry ? Collections.unmodifiableList(IsoThermalRoom.this.openings) : Collections.emptyList();
            this.stairLinks = this.hasLiveGeometry ? Collections.unmodifiableList(IsoThermalRoom.this.stairLinks) : Collections.emptyList();
            this.currentTempHistory = new float[TEMP_HISTORY_CAPACITY];
            this.targetTempHistory = new float[TEMP_HISTORY_CAPACITY];
            this.contributions = new Float[CONTRIB_COUNT];
            Arrays.fill(this.contributions, 0f);
        }

        private void updateCurrentTemp() {
            this.currentTemp = IsoThermalRoom.this.currentTemp;
            this.recordTempHistorySample();
        }

        private void updateTargetTemp() {
            this.targetTemp = IsoThermalRoom.this.targetTemp;
        }

        private void updateGeometry() {
            if (this.hasLiveGeometry) {
                this.squares = Collections.unmodifiableList(IsoThermalRoom.this.squares);
                this.windows = Collections.unmodifiableList(IsoThermalRoom.this.windows);
                this.openings = Collections.unmodifiableList(IsoThermalRoom.this.openings);
                this.stairLinks = Collections.unmodifiableList(IsoThermalRoom.this.stairLinks);
            }
        }

        private void recordTempHistorySample() {
            this.currentTempHistory[this.historyWriteIndex] = this.currentTemp;
            this.targetTempHistory[this.historyWriteIndex] = this.targetTemp;
            this.historyWriteIndex = (this.historyWriteIndex + 1) % TEMP_HISTORY_CAPACITY;
            if (this.historySize < TEMP_HISTORY_CAPACITY) this.historySize++;
        }

        private void setWeightSum(float weightSum) {
            this.weightSum = weightSum;
        }

        private void setWeightedSum(float weightedSum) {
            this.weightedSum = weightedSum;
        }

        private void setTempChangeDelta(float tempChangeDelta) {
            this.tempChangeDelta = tempChangeDelta;
        }

        public float getCurrentTemp() {
            return currentTemp;
        }

        public boolean hasLiveGeometry() {
            return hasLiveGeometry;
        }

        public long getId() {
            return id;
        }

        public boolean isPlayerRoom() {
            return isPlayerRoom;
        }

        public List<RoomOpening> getOpenings() {
            return openings;
        }

        public List<IsoGridSquare> getSquares() {
            return squares;
        }

        public List<StairLink> getStairLinks() {
            return stairLinks;
        }

        public float getTargetTemp() {
            return targetTemp;
        }

        public float getTempChangeDelta() {
            return tempChangeDelta;
        }

        public float getWeightedSum() {
            return weightedSum;
        }

        public float getWeightSum() {
            return weightSum;
        }

        public List<IsoWindow> getWindows() {
            return windows;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getZ() {
            return z;
        }

        public float getWindowCoefficient(IsoWindow window) {
            return IsoThermalRoom.this.calculateWindowCoefficient(window);
        }

        public float getDoorCoefficient(IsoObject object) {
            return IsoThermalRoom.this.calculateDoorCoefficient(object);
        }

        public float getHeatSourceCoefficient(IsoHeatSource heatSource) {
            return Math.clamp(heatSource.getRadius() * RoomTemperatureManager.ThermalConfig.HEATSOURCE_RADIUS_SCALE, RoomTemperatureManager.ThermalConfig.HEATSOURCE_MIN_COEFFICIENT, RoomTemperatureManager.ThermalConfig.HEATSOURCE_MAX_COEFFICIENT);
        }

        public Optional<IsoThermalRoom> getStairConnectedRoom(StairLink stairLink) {
            return IsoThermalRoom.this.getStairConnectedRoom(stairLink);
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

        public Float[] getWeightContributions() {
            return this.contributions;
        }

    }
}
