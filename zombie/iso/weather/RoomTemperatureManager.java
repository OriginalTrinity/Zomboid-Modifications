package zombie.iso.weather;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.jetbrains.annotations.Nullable;
import zombie.GameTime;
import zombie.GameWindow;
import zombie.MainThread;
import zombie.ZomboidFileSystem;
import zombie.characters.IsoPlayer;
import zombie.config.ConfigFile;
import zombie.config.ConfigOption;
import zombie.config.DoubleConfigOption;
import zombie.config.IntegerConfigOption;
import zombie.core.Core;
import zombie.core.ThreadGroups;
import zombie.core.math.PZMath;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.*;
import zombie.iso.areas.IsoRoom;
import zombie.iso.areas.isoregion.IsoRegions;
import zombie.iso.areas.isoregion.data.DataChunk;
import zombie.iso.areas.isoregion.regions.IWorldRegion;
import zombie.iso.areas.isoregion.regions.IsoChunkRegion;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.packets.*;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class RoomTemperatureManager {

    private static final long PERSISTENCE_REFRESH_MS = 5000;
    private static RoomTemperatureManager instance;
    private volatile int regionGeneration;

    private final ArrayList<IsoThermalRoom> simulatedRooms;
    private final ArrayList<PersistentThermalData> staleRooms;
    private final ArrayList<RoomChunkLink> roomChunkLinks;
    private final ArrayDeque<Triple<Double, Float, Float>> outdoorHistory;

    private final Queue<ChunkLifecycleEvent> pendingChunkEvents;
    private final HashMap<IsoPlayer, HashMap<Long, Float>> playerSyncedTemps;
    private final HashMap<Long, Long> pendingRoomRequests;
    private final Set<IsoThermalRoom> pendingRescanRooms;
    private final Queue<IsoGridSquare> pendingSquareChanges;
    private final Set<Long> rescanInProgress;
    private final RoomTileScanWorker tileScanWorker;
    private final Set<Long> pendingPlayerRoomChunks;
    private final HashMap<Long, Pair<Float, Double>> unloadedTemperatureCache;

    private double lastApplyWorldHours = -1;
    private double lastCalculateWorldHours = -1;
    private double lastCleanUpMillis = -1;
    private float groundTemperature = ThermalConfig.GROUND_TEMPERATURE;

    // Client only:
    private long lastPersistenceRequestMillis;
    private boolean remotePersistenceDataReceived;

    public RoomTemperatureManager() {
        this.simulatedRooms = new ArrayList<>();
        this.staleRooms = new ArrayList<>();
        this.roomChunkLinks = new ArrayList<>();
        this.outdoorHistory = new ArrayDeque<>();
        this.pendingChunkEvents = new ConcurrentLinkedQueue<>();
        this.playerSyncedTemps = new HashMap<>();
        this.pendingRoomRequests = new HashMap<>();
        this.pendingRescanRooms = ConcurrentHashMap.newKeySet();
        this.pendingSquareChanges = new ConcurrentLinkedQueue<>();
        this.rescanInProgress = ConcurrentHashMap.newKeySet();
        this.pendingPlayerRoomChunks = new HashSet<>();
        this.unloadedTemperatureCache = new HashMap<>();
        this.tileScanWorker = new RoomTileScanWorker();
        this.tileScanWorker.start();
        ThermalConfig.load();
        instance = this;
    }

    public static RoomTemperatureManager getInstance() {
        return instance;
    }

    public void update() {
        ChunkLifecycleEvent event;
        while ((event = this.pendingChunkEvents.poll()) != null) {
            if (event.loaded()) {
                this.processChunkLoaded(event.wx(), event.wy());
            } else {
                this.processChunkUnloaded(event.wx(), event.wy());
            }
        }

        double now = GameTime.getInstance().getWorldAgeHours();
        if (now - this.lastApplyWorldHours >= ThermalConfig.TEMP_APPLY_INTERVAL_HOURS) {
            this.simulatedRooms.forEach(IsoThermalRoom::applyTemperatureChange);
            if (GameServer.server) {
                this.syncRoomTemperaturesToClients();
            }
            this.lastApplyWorldHours = now;
        }

        if (now - this.lastCalculateWorldHours >= ThermalConfig.TEMP_CALCULATE_INTERVAL_HOURS) {
            this.retryPendingPlayerRoomChunks();
            IsoGridSquare changed;
            while ((changed = this.pendingSquareChanges.poll()) != null) {
                this.processSquareChanged(changed);
            }
            if (!this.pendingRescanRooms.isEmpty()) {
                Set<IsoThermalRoom> toRescan = new HashSet<>(this.pendingRescanRooms);
                this.pendingRescanRooms.removeAll(toRescan);
                for (IsoThermalRoom room : toRescan) {
                    if (this.isRegionStale(room)) {
                        this.evictStaleRoom(room);
                        continue;
                    }
                    if (!room.isPlayerRoom()) {
                        room.refreshSquares();
                        room.scanPerimeter();
                    } else if (this.rescanInProgress.contains(room.getId())) {
                        this.pendingRescanRooms.add(room);
                    } else {
                        this.rescanInProgress.add(room.getId());
                        this.tileScanWorker.enqueue(room);
                    }
                }
            }

            this.recordOutdoorTempSample();
            this.groundTemperature = ThermalConfig.GROUND_TEMPERATURE + ThermalConfig.GROUND_OUTDOOR_FACTOR * (this.averageOutdoorHistory()[0] - ThermalConfig.GROUND_TEMPERATURE);
            this.simulatedRooms.forEach(IsoThermalRoom::calculateTargetTemperature);
            this.lastCalculateWorldHours = now;
        }

        // Runs every hour. Not using world age hours to be unaffected by game speed
        if (System.currentTimeMillis() - lastCleanUpMillis >= TimeUnit.HOURS.toMillis(1)) {
            this.evaluatePersistentThermalDataCleanup();
            this.lastCleanUpMillis = System.currentTimeMillis();
        }
    }

    /** Called on the main thread right after IsoRegions swapped in region data containing these square changes (x, y, z). */
    public void onRegionsRebuilt(List<int[]> squareChanges) {
        this.regionGeneration++;
        if (GameClient.client) return; // clients don't simulate; also covers swaps from server region packets
        // Chunks whose region data was missing (e.g. the whole save right after loading) can only become ready on a swap
        this.retryPendingPlayerRoomChunks();
        for (int[] change : squareChanges) {
            this.applyStructureChange(change[0], change[1], change[2]);
        }
    }

    private void applyStructureChange(int x, int y, int z) {
        IsoCell cell = IsoWorld.instance.getCell();
        if (cell == null) return;
        IsoGridSquare changed = cell.getGridSquare(x, y, z);
        if (changed == null) return; // unloaded in the meantime, chunk streaming handles it on reload

        // The changed square, both sides of any wall on its edges, and the square below (whose roof it may be)
        IsoGridSquare[] affected = {
                changed,
                IsoThermalRoom.getAdjacentSquare(changed, 0, -1), IsoThermalRoom.getAdjacentSquare(changed, 0, 1),
                IsoThermalRoom.getAdjacentSquare(changed, -1, 0), IsoThermalRoom.getAdjacentSquare(changed, 1, 0),
                cell.getGridSquare(x, y, z - 1)};

        for (IsoGridSquare sq : affected) {
            if (sq == null) continue;
            Optional<IsoThermalRoom> existing = this.playerRoomAt(sq);
            if (existing.isPresent()) {
                if (this.isRegionStale(existing.get())) this.evictStaleRoom(existing.get());
                else this.pendingRescanRooms.add(existing.get()); // region may have grown or shrunk
                continue;
            }
            IsoWorldRegion region = getRegionOfSquare(sq);
            if (region != null && this.allChunksLoaded(region)) this.getOrCreateSimulatedRoom(region, sq);
        }
    }

    public void onChunkLoaded(IsoChunk chunk) {
        this.pendingChunkEvents.add(new ChunkLifecycleEvent(chunk.wx, chunk.wy, true));
    }

    public void onChunkUnloaded(IsoChunk chunk) {
        this.pendingChunkEvents.add(new ChunkLifecycleEvent(chunk.wx, chunk.wy, false));
    }

    private void processChunkLoaded(int wx, int wy) {
        for (RoomDef def : this.getRoomsTouchedByChunk(wx, wy)) {
            if (def.isUserDefined()) continue; // generated from player-built regions, handled by discoverPlayerRoomsInChunk
            RoomChunkLink roomChunkLink;
            Optional<RoomChunkLink> optional = this.roomChunkLinks.stream().filter(rcl -> rcl.getRoomId() == def.getID()).findFirst();
            if (optional.isPresent()) roomChunkLink = optional.get();
            else {
                roomChunkLink = new RoomChunkLink(def.getID(), this.countChunksForRoom(def));
                roomChunkLinks.add(roomChunkLink);
            }
            roomChunkLink.incrementLoadedChunks();

            if (roomChunkLink.getLoadedChunks() == roomChunkLink.getTotalChunks()) {
                IsoRoom room = def.getIsoRoom();
                if (room != null) this.getOrCreateSimulatedRoom(room);
            }
        }
        this.discoverPlayerRoomsInChunk(wx, wy);
    }

    private void processChunkUnloaded(int wx, int wy) {
        for (RoomDef def : this.getRoomsTouchedByChunk(wx, wy)) {
            RoomChunkLink roomChunkLink;
            Optional<RoomChunkLink> optional = this.roomChunkLinks.stream().filter(rcl -> rcl.getRoomId() == def.getID()).findFirst();
            if (optional.isEmpty()) continue;
            roomChunkLink = optional.get();

            if (roomChunkLink.getLoadedChunks() == roomChunkLink.getTotalChunks()) {
                this.getSimulatedRoomById(def.getID()).ifPresent(this::evictSimulatedRoom);
            }
            roomChunkLink.decrementLoadedChunks();
            if (roomChunkLink.loadedChunks < 1) {
                roomChunkLinks.remove(roomChunkLink);
            }
        }

        this.pendingPlayerRoomChunks.remove(chunkHash(wx, wy));
        ArrayList<IsoThermalRoom> toEvict = new ArrayList<>();
        for (IsoThermalRoom room : this.simulatedRooms) {
            if (room.isPlayerRoom() && room.occupiesChunk(wx, wy)) toEvict.add(room);
        }
        toEvict.forEach(this::evictSimulatedRoom);
    }

    private ArrayList<RoomDef> getRoomsTouchedByChunk(int wx, int wy) {
        ArrayList<RoomDef> result = new ArrayList<>();
        IsoWorld.instance.getMetaGrid().getRoomsIntersecting(wx * 8 - 1, wy * 8 - 1, 9, 9, result);
        return result;
    }

    private int countChunksForRoom(RoomDef def) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (RoomDef.RoomRect rect : def.getRects()) {
            minX = Math.min(minX, rect.getX());
            minY = Math.min(minY, rect.getY());
            maxX = Math.max(maxX, rect.getX2() - 1);
            maxY = Math.max(maxY, rect.getY2() - 1);
        }
        if (minX > maxX || minY > maxY) return 0;

        int cx0 = Math.floorDiv(minX, 8);
        int cy0 = Math.floorDiv(minY, 8);
        int cx1 = Math.floorDiv(maxX, 8) + 1; // +1: the only direction the 1-tile pad can leak into
        int cy1 = Math.floorDiv(maxY, 8) + 1;

        int count = 0;
        ArrayList<RoomDef> touched = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cy = cy0; cy <= cy1; cy++) {
                touched.clear();
                IsoWorld.instance.getMetaGrid().getRoomsIntersecting(cx * 8 - 1, cy * 8 - 1, 9, 9, touched);
                if (touched.contains(def)) count++;
            }
        }
        return count;
    }

    private void discoverPlayerRoomsInChunk(int wx, int wy) {
        if (IsoRegions.getDataChunk(wx, wy) == null) {
            this.pendingPlayerRoomChunks.add(chunkHash(wx, wy));
            return;
        }
        IsoCell cell = IsoWorld.instance.getCell();
        IsoChunk chunk = cell.getChunkForGridSquare(wx * 8, wy * 8, 0);
        if (chunk == null) return;

        Set<IsoWorldRegion> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int z = Math.max(chunk.getMinLevel(), 0); z <= chunk.getMaxLevel(); z++) {
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                    IsoWorldRegion region = getRegionOfSquare(sq);
                    if (region == null || !seen.add(region) || this.playerRoomAt(sq).isPresent()) continue;
                    if (this.allChunksLoaded(region)) this.getOrCreateSimulatedRoom(region, sq);
                }
            }
        }
    }

    private boolean allChunksLoaded(IsoWorldRegion region) {
        IsoCell cell = IsoWorld.instance.getCell();
        for (IsoChunkRegion chunkRegion : region.getChunkRegions()) {
            DataChunk dataChunk = chunkRegion.getDataChunk();
            if (cell.getChunkForGridSquare(dataChunk.getChunkX() * 8, dataChunk.getChunkY() * 8, 0) == null) return false;
        }
        return true;
    }

    private void retryPendingPlayerRoomChunks() {
        if (this.pendingPlayerRoomChunks.isEmpty()) return;
        List<Long> retry = new ArrayList<>(this.pendingPlayerRoomChunks);
        this.pendingPlayerRoomChunks.clear();
        for (long hash : retry) {
            this.discoverPlayerRoomsInChunk((int) (hash >> 32), (int) hash);
        }
    }

    private static long chunkHash(int wx, int wy) {
        return ((long) wx << 32) | (wy & 0xFFFFFFFFL);
    }

    public void onSquareChanged(IsoGridSquare square) {
        this.pendingSquareChanges.add(square);
    }

    private void processSquareChanged(IsoGridSquare square) {
        if (square == null) return;
        this.enqueueRescanIfSimulated(square);
        this.enqueueRescanIfSimulated(IsoThermalRoom.getAdjacentSquare(square, 0, -1));
        this.enqueueRescanIfSimulated(IsoThermalRoom.getAdjacentSquare(square, 0, 1));
        this.enqueueRescanIfSimulated(IsoThermalRoom.getAdjacentSquare(square, -1, 0));
        this.enqueueRescanIfSimulated(IsoThermalRoom.getAdjacentSquare(square, 1, 0));
    }

    private void enqueueRescanIfSimulated(IsoGridSquare square) {
        this.getSimulatedRoom(square).ifPresent(this.pendingRescanRooms::add);
    }

    private boolean isRegionStale(IsoThermalRoom room) {
        if (!room.isPlayerRoom() || room.getSquares().isEmpty()) return false;
        IsoWorldRegion current = getRegionOfSquare(room.getSquares().get(0));
        return current == null || current.getID() < 0;
    }

    private IsoThermalRoom getOrCreateSimulatedRoom(IsoRoom room) {
        return this.getSimulatedRoomById(room.getRoomDef().getID())
                .orElseGet(() -> this.promoteNewRoom(new IsoThermalRoom(room)));
    }

    private IsoThermalRoom getOrCreateSimulatedRoom(IsoWorldRegion region, IsoGridSquare seed) {
        Optional<IsoThermalRoom> existing = this.playerRoomAt(seed);
        if (existing.isPresent()) return existing.get();
        IsoThermalRoom room = new IsoThermalRoom(region, seed);
        // A room already covering part of this region (e.g. regions merged) absorbs it through a rescan instead
        Optional<IsoThermalRoom> overlapping = this.simulatedRooms.stream()
                .filter(r -> r.isPlayerRoom() && room.getSquares().stream().anyMatch(r::containsSquare))
                .findFirst();
        if (overlapping.isPresent()) {
            this.pendingRescanRooms.add(overlapping.get());
            return overlapping.get();
        }
        return this.promoteNewRoom(room);
    }

    public Optional<IsoThermalRoom> getSimulatedRoom(IsoGridSquare square) {
        if (square == null) return Optional.empty();
        IsoRoom room = getMappedRoom(square);
        if (room != null) return this.getSimulatedRoomById(room.getRoomDef().getID());
        return this.playerRoomAt(square);
    }

    private IsoThermalRoom promoteNewRoom(IsoThermalRoom room) {
        Optional<PersistentThermalData> optionalPtd = this.getPersistentThermalDataFromCoordinates(room.getX(), room.getY(), room.getZ(), room.isPlayerRoom());
        if (optionalPtd.isPresent()) {
            PersistentThermalData ptd = optionalPtd.get();
            room.setCurrentTemperature(ptd.lastTemp());
            room.setLastUpdate(ptd.lastUpdate());
            this.catchUpStaleRoom(room);
            this.staleRooms.remove(ptd);
        }
        this.simulatedRooms.add(room);
        return room;
    }

    private void recordOutdoorTempSample() {
        double now = GameTime.getInstance().getWorldAgeHours();
        Triple<Double, Float, Float> last = this.outdoorHistory.peekLast();
        if (last == null || now - last.getLeft() >= ThermalConfig.OUTDOOR_SAMPLE_INTERVAL_HOURS) {
            this.outdoorHistory.addLast(Triple.of(now, ClimateManager.getInstance().getTemperature(), getCurrentSunStrength()));
        }
        while (!this.outdoorHistory.isEmpty() && now - this.outdoorHistory.peekFirst().getLeft() > ThermalConfig.OUTDOOR_HISTORY_MAX_HOURS) {
            this.outdoorHistory.pollFirst();
        }
    }

    private float[] averageOutdoorHistory() {
        if (this.outdoorHistory.isEmpty()) return new float[]{ClimateManager.getInstance().getTemperature(), getCurrentSunStrength()};
        float temp = 0.0f;
        float sun = 0.0f;
        for (Triple<Double, Float, Float> sample : this.outdoorHistory) {
            temp += sample.getMiddle();
            sun += sample.getRight();
        }
        return new float[]{temp / this.outdoorHistory.size(), sun / this.outdoorHistory.size()};
    }

    private void catchUpStaleRoom(IsoThermalRoom room) {
        double now = GameTime.getInstance().getWorldAgeHours();
        double cursor = room.getLastUpdate();
        if (cursor >= now) {
            room.setLastUpdate(now);
            return;
        }

        Triple<Double, Float, Float> oldest = this.outdoorHistory.peekFirst();
        if (oldest != null && cursor < oldest.getLeft()) {
            float[] avg = this.averageOutdoorHistory();
            room.calculateTargetTemperature(avg[0], avg[1]);
            room.stepTemperature(oldest.getLeft() - cursor);
            cursor = oldest.getLeft();
        }
        for (Triple<Double, Float, Float> sample : this.outdoorHistory) {
            if (sample.getLeft() <= cursor) continue;
            room.calculateTargetTemperature(sample.getMiddle(), sample.getRight());
            room.stepTemperature(sample.getLeft() - cursor);
            cursor = sample.getLeft();
        }

        room.calculateTargetTemperature();
        room.stepTemperature(now - cursor);
        room.setLastUpdate(now);
    }

    private void invalidateSyncedRoom(long id) {
        this.playerSyncedTemps.values().forEach(known -> known.remove(id));
    }

    private void notifyRoomRemoved(long id) {
        for (Map.Entry<IsoPlayer, HashMap<Long, Float>> entry : this.playerSyncedTemps.entrySet()) {
            if (entry.getValue().remove(id) == null) continue;
            UdpConnection connection = GameServer.getConnectionFromPlayer(entry.getKey());
            if (connection != null) INetworkPacket.send(connection, PacketTypes.PacketType.RoomThermalRemove, List.of(id));
        }
    }

    private void evictSimulatedRoom(IsoThermalRoom room) {
        if (!this.simulatedRooms.remove(room)) return;
        this.notifyRoomRemoved(room.getId());
        PersistentThermalData ptd = PersistentThermalData.of(room);
        this.staleRooms.remove(ptd);
        this.staleRooms.add(ptd);
    }

    private void evictStaleRoom(IsoThermalRoom room) {
        this.evictSimulatedRoom(room);
        if (!room.isPlayerRoom()) return;
        for (long hash : room.getSquareHashes()) {
            this.pendingPlayerRoomChunks.add(chunkHash(IsoThermalRoom.unpackX(hash) / 8, IsoThermalRoom.unpackY(hash) / 8));
        }
    }

    public List<IsoThermalRoom> getSimulatedRooms() {
        return Collections.unmodifiableList(this.simulatedRooms);
    }

    public List<PersistentThermalData> getPersistentThermalData() {
        return Collections.unmodifiableList(this.staleRooms);
    }

    public Optional<IsoThermalRoom> getSimulatedRoomById(long id) {
        return this.simulatedRooms.stream().filter(room -> room.getId() == id).findFirst();
    }

    public float getSimulatedTemperature(IsoRoom room) {
        return this.getSimulatedRoomById(room.getRoomDef().getID())
                .map(IsoThermalRoom::getCurrentTemperature)
                .orElse(ClimateManager.getInstance().getTemperature());
    }

    public float getSimulatedTemperature(IsoGridSquare square) {
        return this.playerRoomAt(square)
                .map(IsoThermalRoom::getCurrentTemperature)
                .orElse(ClimateManager.getInstance().getTemperature());
    }

    private Optional<PersistentThermalData> getPersistentThermalDataFromCoordinates(int x, int y, int z, boolean isPlayerRoom) {
        Optional<PersistentThermalData> optional = this.staleRooms.stream()
                .filter(ptd -> ptd.x() == x && ptd.y() == y && ptd.z() == z && ptd.isPlayerRoom() == isPlayerRoom)
                .findFirst();
        if (optional.isEmpty() && isPlayerRoom) {
            optional = this.findNearestPersistentThermalData(x, y, z);
        }
        return optional;
    }

    private Optional<PersistentThermalData> findNearestPersistentThermalData(int x, int y, int z) {
        PersistentThermalData best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (PersistentThermalData ptd : this.staleRooms) {
            if (!ptd.isPlayerRoom || ptd.z() != z) continue;
            double dx = ptd.x() - x;
            double dy = ptd.y() - y;
            double distSq = dx * dx + dy * dy;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = ptd;
            }
        }
        double maxDistSq = ThermalConfig.PLAYER_ROOM_STALE_MATCH_RADIUS * ThermalConfig.PLAYER_ROOM_STALE_MATCH_RADIUS;
        return (best != null && bestDistSq <= maxDistSq) ? Optional.of(best) : Optional.empty();
    }

    public static IsoWorldRegion getRegionOfSquare(IsoGridSquare sq) {
        if (sq == null || getMappedRoom(sq) != null) return null; // mapped-building squares belong to their IsoRoom, even though their regions are enclosed + roofed too
        IWorldRegion candidate = sq.getIsoWorldRegion();
        if (candidate != null && candidate.isPlayerRoom()) {
            return (IsoWorldRegion) candidate;
        }
        return null;
    }

    public Optional<IsoThermalRoom> resolveNeighborRoom(IsoThermalRoom.RoomOpening opening) {
        if (opening.neighborRoom() != null) {
            return this.getSimulatedRoomById(opening.neighborRoom().getRoomDef().getID());
        }
        if (opening.neighborRegion() != null) {
            return this.playerRoomAt(opening.neighborSquare());
        }
        return Optional.empty();
    }

    private ArrayList<IsoThermalRoom> getRelevantRooms(IsoPlayer player) {
        ArrayList<IsoThermalRoom> relevant = new ArrayList<>();
        int px = PZMath.fastfloor(player.getX());
        int py = PZMath.fastfloor(player.getY());

        double maxDistSq = ThermalConfig.ROOM_SYNC_RELEVANCE_RADIUS * ThermalConfig.ROOM_SYNC_RELEVANCE_RADIUS;

        for (IsoThermalRoom room : this.simulatedRooms) {
            double dx = room.getX() - px;
            double dy = room.getY() - py;
            if (dx * dx + dy * dy <= maxDistSq) relevant.add(room);
        }
        return relevant;
    }

    private Optional<IsoThermalRoom> playerRoomAt(IsoGridSquare sq) {
        if (sq == null) return Optional.empty();
        return this.simulatedRooms.stream().filter(r -> r.isPlayerRoom() && r.containsSquare(sq)).findFirst();
    }

    private static RoomThermalSnapshotPacket.RoomThermalStateSnapshot snapshotOf(IsoThermalRoom room) {
        return new RoomThermalSnapshotPacket.RoomThermalStateSnapshot(room.getId(), room.getX(), room.getY(), room.getZ(), room.isPlayerRoom(), room.getCurrentTemperature(), room.isPlayerRoom() ? room.getSquareHashes() : null);
    }

    private void syncRoomTemperaturesToClients() {
        for (IsoPlayer player : GameServer.getPlayers()) {
            UdpConnection connection = GameServer.getConnectionFromPlayer(player);
            if (connection == null) continue;

            if (!this.playerSyncedTemps.containsKey(player)) {
                INetworkPacket.send(connection, PacketTypes.PacketType.RoomThermalConfig, RoomThermalConfigPacket.ACTION_SYNC);
            }

            HashMap<Long, Float> knownTemps = this.playerSyncedTemps.computeIfAbsent(player, p -> new HashMap<>());
            ArrayList<IsoThermalRoom> relevant = this.getRelevantRooms(player);
            HashSet<Long> relevantIds = new HashSet<>();

            ArrayList<RoomThermalSnapshotPacket.RoomThermalStateSnapshot> snapshotBatch = new ArrayList<>();
            ArrayList<RoomThermalDeltaPacket.RoomThermalDeltaEntry> deltaBatch = new ArrayList<>();

            for (IsoThermalRoom state : relevant) {
                relevantIds.add(state.getId());
                Float lastTemp = knownTemps.get(state.getId());
                if (lastTemp == null) {
                    snapshotBatch.add(snapshotOf(state));
                    knownTemps.put(state.getId(), state.getCurrentTemperature());
                } else if (Math.abs(state.getCurrentTemperature() - lastTemp) >= ThermalConfig.TEMP_SYNC_EPSILON) {
                    deltaBatch.add(new RoomThermalDeltaPacket.RoomThermalDeltaEntry(state.getId(), state.getCurrentTemperature()));
                    knownTemps.put(state.getId(), state.getCurrentTemperature());
                }
            }

            // Player moved away, drop from the synced set and send removal packets
            ArrayList<Long> removedIds = new ArrayList<>();
            knownTemps.keySet().removeIf(id -> {
                if (relevantIds.contains(id)) return false;
                removedIds.add(id);
                return true;
            });

            if (!snapshotBatch.isEmpty()) INetworkPacket.send(connection, PacketTypes.PacketType.RoomThermalSnapshot, snapshotBatch);
            if (!deltaBatch.isEmpty()) INetworkPacket.send(connection, PacketTypes.PacketType.RoomThermalDelta, deltaBatch);
            if (!removedIds.isEmpty()) INetworkPacket.send(connection, PacketTypes.PacketType.RoomThermalRemove, removedIds);
        }
    }

    public void applySnapshotFromServer(long id, int x, int y, int z, boolean isPlayerRoom, float currentTemp, Set<Long> squareHashes) {
        Optional<IsoThermalRoom> existing = this.getSimulatedRoomById(id);
        if (existing.isPresent()) {
            existing.get().setCurrentTemperature(currentTemp);
            existing.get().setSquareHashes(squareHashes);
        } else {
            this.simulatedRooms.add(new IsoThermalRoom(id, x, y, z, isPlayerRoom, currentTemp, squareHashes));
        }
        this.pendingRoomRequests.remove(id);
    }

    public void applyDeltaFromServer(long id, float currentTemp) {
        this.getSimulatedRoomById(id).ifPresentOrElse(
                thermalState -> thermalState.setCurrentTemperature(currentTemp),
                () -> this.requestRoomSnapshot(id));
    }

    public void applyRemovalFromServer(List<Long> ids) {
        this.simulatedRooms.removeIf(room -> ids.contains(room.getId()));
        ids.forEach(this.pendingRoomRequests::remove);
    }

    private void requestRoomSnapshot(long id) {
        long now = System.currentTimeMillis();
        Long lastRequested = this.pendingRoomRequests.get(id);
        if (lastRequested != null && now - lastRequested < ThermalConfig.ROOM_REQUEST_COOLDOWN) return;
        this.pendingRoomRequests.put(id, now);
        INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.RoomThermalDataRequest, List.of(id));
    }

    public void removePlayerFromCache(IsoPlayer player) {
        this.playerSyncedTemps.remove(player);
    }

    public void handleRoomThermalDataRequest(IsoPlayer player, List<Long> requestedIds) {
        UdpConnection connection = GameServer.getConnectionFromPlayer(player);
        if (connection == null) return;

        HashMap<Long, Float> knownTemps = this.playerSyncedTemps.computeIfAbsent(player, p -> new HashMap<>());
        ArrayList<RoomThermalSnapshotPacket.RoomThermalStateSnapshot> snapshotBatch = new ArrayList<>();

        for (long id : requestedIds) {
            this.getSimulatedRoomById(id).ifPresent(thermalState -> {
                snapshotBatch.add(snapshotOf(thermalState));
                knownTemps.put(thermalState.getId(), thermalState.getCurrentTemperature());
            });
        }

        if (!snapshotBatch.isEmpty()) {
            INetworkPacket.send(connection, PacketTypes.PacketType.RoomThermalSnapshot, snapshotBatch);
        }
    }

    public void requestPersistentThermalData() {
        if (!GameClient.client) return;
        long now = System.currentTimeMillis();
        if (now - this.lastPersistenceRequestMillis < PERSISTENCE_REFRESH_MS) return;
        this.lastPersistenceRequestMillis = now;
        INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.RoomThermalPersistence, RoomThermalPersistencePacket.ACTION_VIEW);
    }

    public void applyPersistentThermalDataFromServer(List<PersistentThermalData> data) {
        this.staleRooms.clear();
        this.staleRooms.addAll(data);
        this.remotePersistenceDataReceived = true;
    }

    public void clearPersistentThermalData() {
        if (GameClient.client) {
            INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.RoomThermalPersistence, RoomThermalPersistencePacket.ACTION_CLEANUP);
            return;
        }
        this.staleRooms.clear();
    }

    private void savePersistentThermalData(List<PersistentThermalData> data) {
        File outFile = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("thermalSim.bin"));
        File tmpFile = new File(outFile.getPath() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmpFile)))) {
            output.writeBytes("RMTM"); // Identifier
            output.writeShort(2); // Version number
            output.writeInt(data.size());
            for (PersistentThermalData ptd : data) this.savePersistentThermalData(output, ptd);
            this.saveOutdoorTemperatureHistory(output);
        } catch (IOException e) {
            DebugType.General.printException(e, "Failed to save PersistentThermalData", LogSeverity.Error);
            return;
        }
        try {
            Files.move(tmpFile.toPath(), outFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            DebugType.General.printException(e, "Failed to overwrite old save file!", LogSeverity.Error);
        }
    }

    private void savePersistentThermalData(DataOutputStream output, PersistentThermalData ptd) throws IOException {
        output.writeInt(ptd.x());
        output.writeInt(ptd.y());
        output.writeInt(ptd.z());
        output.writeFloat(ptd.lastTemp());
        output.writeDouble(ptd.lastUpdate());
        output.writeBoolean(ptd.isPlayerRoom());
    }

    private void saveOutdoorTemperatureHistory(DataOutputStream output) throws IOException {
        output.writeInt(this.outdoorHistory.size());
        for (Triple<Double, Float, Float> pair : this.outdoorHistory) {
            output.writeDouble(pair.getLeft());
            output.writeFloat(pair.getMiddle());
            output.writeFloat(pair.getRight());
        }
    }

    public void saveAll() {
        if (GameClient.client || Core.getInstance().isNoSave()) return;
        LinkedHashSet<PersistentThermalData> snapshot = new LinkedHashSet<>(this.staleRooms);
        for (IsoThermalRoom room : this.simulatedRooms) {
            PersistentThermalData ptd = PersistentThermalData.of(room);
            snapshot.remove(ptd);
            snapshot.add(ptd);
        }
        this.savePersistentThermalData(new ArrayList<>(snapshot));
    }

    public void loadPersistentThermalData() {
        File inFile = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("thermalSim.bin"));
        if (!inFile.exists()) return;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(inFile)))) {
            byte b1 = input.readByte();
            byte b2 = input.readByte();
            byte b3 = input.readByte();
            byte b4 = input.readByte();
            short version = input.readShort();

            if (b1 != 82 || b2 != 77 || b3 != 84 || b4 != 77 || version != 2) {
                DebugType.FileIO.println("Saved Persistent Thermal Data does not match current version!");
                return;
            }
            int ptdCount = input.readInt();
            for (int i = 0; i < ptdCount; i++) {
                int x = input.readInt();
                int y = input.readInt();
                int z = input.readInt();
                float lastTemp = input.readFloat();
                double lastUpdate = input.readDouble();
                boolean isPlayerRoom = input.readBoolean();

                PersistentThermalData ptd = new PersistentThermalData(x, y, z, lastTemp, lastUpdate, isPlayerRoom);
                if (this.staleRooms.stream().noneMatch(ptd::equals)) this.staleRooms.add(ptd);
            }
            int historyCount = input.readInt();
            for (int i = 0; i < historyCount; i++) {
                double timeStamp = input.readDouble();
                float temp = input.readFloat();
                float sun = input.readFloat();

                this.outdoorHistory.add(Triple.of(timeStamp, temp, sun));
            }
        } catch (IOException e) {
            DebugType.General.printException(e, "Failed to load PersistentThermalData!", LogSeverity.Error);
        }
    }

    public void evaluatePersistentThermalDataCleanup() {
        this.staleRooms.removeIf(ptd -> GameTime.getInstance().getWorldAgeHours() - ptd.lastUpdate >= ThermalConfig.PERSISTENT_THERMAL_DATA_MAX_AGE);
    }

    public void reset() {
        this.simulatedRooms.clear();
        this.staleRooms.clear();
        this.roomChunkLinks.clear();
        this.outdoorHistory.clear();
        this.playerSyncedTemps.clear();
        this.pendingRoomRequests.clear();
        this.pendingPlayerRoomChunks.clear();
        this.lastApplyWorldHours = -1;
        this.lastCalculateWorldHours = -1;
        this.lastCleanUpMillis = -1;
        this.tileScanWorker.stop();
        this.unloadedTemperatureCache.clear();
        this.lastPersistenceRequestMillis = 0;
        this.remotePersistenceDataReceived = false;

        ThermalConfig.load();
    }

    public static IsoRoom getMappedRoom(IsoGridSquare sq) {
        IsoRoom room = sq != null ? sq.getRoom() : null;
        return room != null && room.getRoomDef() != null && !room.getRoomDef().isUserDefined() ? room : null;
    }

    public float applyHeatSourceProximity(float airTemp, IsoGridSquare square) {
        IsoCell cell = IsoWorld.instance.getCell();
        float bestBonus = 0.0f;
        for (IsoHeatSource heatSource : cell.getHeatSources()) {
            if (heatSource.getZ() != square.getZ()) continue;
            float radius = Math.min(heatSource.getRadius(), ThermalConfig.HEATSOURCE_PROXIMITY_RADIUS);
            float dist = IsoUtils.DistanceTo(square.getX(), square.getY(), heatSource.getX(), heatSource.getY());
            if (radius <= 0 || dist >= radius) continue;
            float bonus = (float) (Math.max(0.0f, heatSource.getTemperature() - airTemp) * ThermalConfig.HEATSOURCE_PROXIMITY_STRENGTH * (1.0 - dist / radius));
            if (bonus <= bestBonus) continue;
            LosUtil.TestResults los = LosUtil.lineClear(cell, heatSource.getX(), heatSource.getY(), heatSource.getZ(), square.getX(), square.getY(), square.getZ(), false);
            if (los == LosUtil.TestResults.Clear || los == LosUtil.TestResults.ClearThroughOpenDoor) {
                bestBonus = bonus;
            }
        }
        return airTemp + bestBonus;
    }

    public float getGroundTemperature() {
        return this.groundTemperature;
    }

    public static float getCurrentSunStrength() {
        ClimateManager climateManager = ClimateManager.getInstance();
        return climateManager.getDayLightStrength() * (1.0f - climateManager.getCloudIntensity());
    }

    public boolean hasPersistentThermalData() {
        return !GameClient.client || this.remotePersistenceDataReceived;
    }

    public float getLastKnownTemperatureAt(float fx, float fy, float fz) {
        int x = PZMath.fastfloor(fx), y = PZMath.fastfloor(fy), z = PZMath.fastfloor(fz);
        long key = IsoThermalRoom.packCoordinates(x, y, z);
        double now = GameTime.getInstance().getWorldAgeHours();
        Pair<Float, Double> cached = this.unloadedTemperatureCache.get(key);
        if (cached != null && now - cached.getRight() < ThermalConfig.TEMP_CALCULATE_INTERVAL_HOURS) {
            return cached.getLeft();
        }
        float temperature = this.lookupLastKnownTemperature(x, y, z);
        this.unloadedTemperatureCache.put(key, Pair.of(temperature, now));
        return temperature;
    }

    private float lookupLastKnownTemperature(int x, int y, int z) {
        float outdoor = ClimateManager.getInstance().getTemperature();

        RoomDef def = IsoWorld.instance.getMetaGrid().getRoomAt(x, y, z);
        if (def != null && !def.isUserDefined()) {
            Optional<IsoThermalRoom> live = this.getSimulatedRoomById(def.getID());
            if (live.isPresent()) return live.get().getCurrentTemperature();
            return this.getPersistentThermalDataFromCoordinates(def.getX(), def.getY(), def.getZ(), false)
                    .map(PersistentThermalData::lastTemp)
                    .orElse(outdoor);
        }

        if (IsoRegions.getIsoWorldRegion(x, y, z) instanceof IsoWorldRegion region && region.isPlayerRoom()) {
            for (IsoThermalRoom room : this.simulatedRooms) {
                if (room.isPlayerRoom() && room.containsSquare(x, y, z)) return room.getCurrentTemperature();
            }
            int[] corner = findRegionCorner(region);
            if (corner != null) {
                return this.getPersistentThermalDataFromCoordinates(corner[0], corner[1], z, true)
                        .map(PersistentThermalData::lastTemp)
                        .orElse(outdoor);
            }
        }

        return outdoor;
    }

    private static int[] findRegionCorner(IsoWorldRegion region) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (IsoChunkRegion chunkRegion : region.getChunkRegions()) {
            DataChunk dataChunk = chunkRegion.getDataChunk();
            int z = chunkRegion.getzLayer();
            for (int dx = 0; dx < 8; dx++) {
                for (int dy = 0; dy < 8; dy++) {
                    int sx = dataChunk.getChunkX() * 8 + dx, sy = dataChunk.getChunkY() * 8 + dy;
                    if (IsoRegions.getIsoWorldRegion(sx, sy, z) == region) {
                        minX = Math.min(minX, sx);
                        minY = Math.min(minY, sy);
                    }
                }
            }
        }

        return minX == Integer.MAX_VALUE ? null : new int[]{minX, minY};
    }

    public record PersistentThermalData(int x, int y, int z, float lastTemp, double lastUpdate, boolean isPlayerRoom) {

        public void write(ByteBufferWriter b) {
            b.putInt(this.x);
            b.putInt(this.y);
            b.putInt(this.z);
            b.putFloat(this.lastTemp);
            b.putDouble(this.lastUpdate);
            b.putBoolean(this.isPlayerRoom);
        }

        public static PersistentThermalData read(ByteBufferReader b) {
            return new PersistentThermalData(b.getInt(), b.getInt(), b.getInt(), b.getFloat(), b.getDouble(), b.getBoolean());
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof PersistentThermalData ptd) {
                return this.x == ptd.x && this.y == ptd.y && this.z == ptd.z && this.isPlayerRoom == ptd.isPlayerRoom;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.x, this.y, this.z, this.isPlayerRoom);
        }

        public static PersistentThermalData of(IsoThermalRoom room) {
            return new PersistentThermalData(room.getX(), room.getY(), room.getZ(), room.getCurrentTemperature(), room.getLastUpdate(), room.isPlayerRoom());
        }

    }

    private static class RoomChunkLink {
        private final int totalChunks;
        private int loadedChunks;
        private final long roomId;

        public RoomChunkLink(long roomId, int totalChunks) {
            this.roomId = roomId;
            this.totalChunks = totalChunks;
        }

        public int getTotalChunks() {
            return this.totalChunks;
        }

        public int getLoadedChunks() {
            return this.loadedChunks;
        }

        public void incrementLoadedChunks() {
            this.loadedChunks++;
        }

        public void decrementLoadedChunks() {
            this.loadedChunks--;
        }

        public long getRoomId() {
            return this.roomId;
        }
    }

    private record ChunkLifecycleEvent(int wx, int wy, boolean loaded) {}

    public static class ThermalConfig {
        private static int revision;

        // Global constants
        public static int PLAYER_ROOM_STALE_MATCH_RADIUS;
        public static int ROOM_SYNC_RELEVANCE_RADIUS;
        public static float TEMP_SYNC_EPSILON;
        public static int ROOM_REQUEST_COOLDOWN;
        public static float OUTDOOR_SAMPLE_INTERVAL_HOURS;
        public static int OUTDOOR_HISTORY_MAX_HOURS;
        public static float TEMP_APPLY_INTERVAL_HOURS;   // ~1 game-minute
        public static float TEMP_CALCULATE_INTERVAL_HOURS; // ~6 game-minutes
        public static int PERSISTENT_THERMAL_DATA_MAX_AGE;

        // IsoThermalRoom constants
        public static float BASE_COEFFICIENT;
        public static float WINDOW_CLOSED_COEFFICIENT;
        public static float WINDOW_CURTAIN_MULTIPLIER;
        public static float WINDOW_OPEN_COEFFICIENT;
        public static float BARRICADE_INSULATION_MULTIPLIER;
        public static float DOOR_CLOSED_COEFFICIENT;
        public static float DOOR_OPEN_COEFFICIENT;
        public static float BREACH_COEFFICIENT;
        public static float STAIR_LINK_COEFFICIENT;

        public static float HEATSOURCE_MIN_COEFFICIENT;
        public static float HEATSOURCE_MAX_COEFFICIENT;
        public static float HEATSOURCE_RADIUS_SCALE;
        public static int HEATSOURCE_PROXIMITY_RADIUS;
        public static float HEATSOURCE_PROXIMITY_STRENGTH;
        public static float CLIMATE_CONTROL_COEFFICIENT;

        public static float TEMP_CHANGE_RATE_MULTIPLIER;
        public static float INTERROOM_TRANSFER_MULTIPLIER;
        public static float HEATING_RATE_MULTIPLIER;
        public static float COOLING_RATE_MULTIPLIER;
        public static float MAX_TEMP_DELTA;

        // Environment constants
        public static float UPPER_FLOOR_TEMP_DROP = 1.0f; // °C colder outdoor air per z-level > 0
        public static float SOLAR_ROOF_GAIN = 8.0f; // °C added to a roof-exposed room's outdoor air at full sun
        public static float GROUND_TEMPERATURE = 10.0f; // neutral ground temperature
        public static float GROUND_OUTDOOR_FACTOR = 0.4f;
        public static float BASEMENT_GROUND_COEFFICIENT = 5.0f;
        public static float BASEMENT_OUTDOOR_FACTOR = 0.2f;
        public static float MIN_FLOOR_OUTDOOR_MULTIPLIER = 0.1f;

        public static final List<Option<? extends Number>> OPTIONS = List.of(
                Option.ofInteger("PLAYER_ROOM_STALE_MATCH_RADIUS", "Max tiles between a rebuilt player room and its saved temperature to still match", Option.Type.GLOBAL, 1, 256, 32),
                Option.ofInteger("ROOM_SYNC_RELEVANCE_RADIUS", "Rooms within this many tiles of a player are synced to their client", Option.Type.GLOBAL, 1, 256, 80),
                Option.ofFloat("TEMP_SYNC_EPSILON", "Minimum temperature change (°C) before an update is sent to clients", Option.Type.GLOBAL, 0.001f, Float.MAX_VALUE, 0.05f),
                Option.ofInteger("ROOM_REQUEST_COOLDOWN", "Milliseconds before a client may request the same unknown room again", Option.Type.GLOBAL, 1, Integer.MAX_VALUE, 5000),
                Option.ofFloat("OUTDOOR_SAMPLE_INTERVAL_HOURS", "Game hours between recorded outdoor temperature samples", Option.Type.GLOBAL, 0.01f, Float.MAX_VALUE, 1.0f),
                Option.ofInteger("OUTDOOR_HISTORY_MAX_HOURS", "Max number of game hours a outdoor sample is kept", Option.Type.GLOBAL, 1, 8760, 72),
                Option.ofFloat("TEMP_APPLY_INTERVAL_HOURS", "Game hours between room temperature steps", Option.Type.GLOBAL, 0.0f, Float.MAX_VALUE, 0.02f),
                Option.ofFloat("TEMP_CALCULATE_INTERVAL_HOURS", "Game hours between target temperature recalculations", Option.Type.GLOBAL, 0.0f, Float.MAX_VALUE, 0.1f),
                Option.ofInteger("PERSISTENT_THERMAL_DATA_MAX_AGE", "Max number of game hours a room's thermal state is kept saved", Option.Type.GLOBAL, 1, 8760, 720),
                Option.ofFloat("BASE_COEFFICIENT", "Heat exchange with the outdoors through walls and roof", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 0.05f),
                Option.ofFloat("WINDOW_CLOSED_COEFFICIENT", "Heat exchange through a closed window", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 0.03f),
                Option.ofFloat("WINDOW_CURTAIN_MULTIPLIER", "Multiplier on a window's exchange while its curtains are closed", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 0.6f),
                Option.ofFloat("WINDOW_OPEN_COEFFICIENT", "Heat exchange through an open or smashed window", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 0.5f),
                Option.ofFloat("BARRICADE_INSULATION_MULTIPLIER", "How strongly barricades reduce window exchange (0 = no effect)", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 1.0f),
                Option.ofFloat("DOOR_CLOSED_COEFFICIENT", "Heat exchange through a closed door", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 0.02f),
                Option.ofFloat("DOOR_OPEN_COEFFICIENT", "Heat exchange through an open door", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 0.95f),
                Option.ofFloat("BREACH_COEFFICIENT", "Heat exchange through gaps, wall frames, empty window frames and destroyed doors", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 1.0f),
                Option.ofFloat("STAIR_LINK_COEFFICIENT", "Heat exchange between floors connected by stairs", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 3.0f),
                Option.ofFloat("HEATSOURCE_MIN_COEFFICIENT", "Lowest coefficient a heat source can have", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 0.05f),
                Option.ofFloat("HEATSOURCE_MAX_COEFFICIENT", "Highest coefficient a heat source can have", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 10.0f),
                Option.ofFloat("HEATSOURCE_RADIUS_SCALE", "How strongly heat source radius correlates to its coefficient", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 1.0f),
                Option.ofInteger("HEATSOURCE_PROXIMITY_RADIUS", "Max tiles from a heat source at which characters feel extra warmth", Option.Type.ROOM, 1, Integer.MAX_VALUE, 3),
                Option.ofFloat("HEATSOURCE_PROXIMITY_STRENGTH", "Share of the gap to the heat source's temperature felt right next to it", Option.Type.ROOM, 0.01f, Float.MAX_VALUE, 0.5f),
                Option.ofFloat("CLIMATE_CONTROL_COEFFICIENT", "AC strength while the world power is on", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 8.0f),
                Option.ofFloat("TEMP_CHANGE_RATE_MULTIPLIER", "Overall speed at which rooms approach their target temperature", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 20.0f),
                Option.ofFloat("INTERROOM_TRANSFER_MULTIPLIER", "Multiplier on heat exchange through openings and stairs between rooms", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 5.0f),
                Option.ofFloat("HEATING_RATE_MULTIPLIER", "Speed multiplier while a room is warming up", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 1.0f),
                Option.ofFloat("COOLING_RATE_MULTIPLIER", "Speed multiplier while a room is cooling down", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 1.0f),
                Option.ofFloat("MAX_TEMP_DELTA", "Maximum temperature change per game hour (°C)", Option.Type.ROOM, 0.0f, Float.MAX_VALUE, 10.0f),
                Option.ofFloat("UPPER_FLOOR_TEMP_DROP", "°C colder outdoor air per floor above ground", Option.Type.ENVIRONMENT, 0.0f, Float.MAX_VALUE, 1.0f),
                Option.ofFloat("SOLAR_ROOF_GAIN", "°C added to the outdoor air of roof-exposed rooms in full sunshine", Option.Type.ENVIRONMENT, 0.0f, Float.MAX_VALUE, 8.0f),
                Option.ofFloat("GROUND_TEMPERATURE", "Neutral ground temperature basements are pulled towards", Option.Type.ENVIRONMENT, -Float.MAX_VALUE, Float.MAX_VALUE, 10.0f),
                Option.ofFloat("GROUND_OUTDOOR_FACTOR", "How much the ground follows the outdoor average (0 = fixed, 1 = fully)", Option.Type.ENVIRONMENT, 0.0f, 1.0f, 0.4f),
                Option.ofFloat("BASEMENT_GROUND_COEFFICIENT", "Pull towards the ground temperature per level below ground", Option.Type.ENVIRONMENT, 0.0f, Float.MAX_VALUE, 5.0f),
                Option.ofFloat("BASEMENT_OUTDOOR_FACTOR", "Reduction of outdoor heat exchange per level below ground", Option.Type.ENVIRONMENT, 0.0f, Float.MAX_VALUE, 0.2f),
                Option.ofFloat("MIN_FLOOR_OUTDOOR_MULTIPLIER", "Lowest outdoor exchange multiplier a basement can reach", Option.Type.ENVIRONMENT, 0.0f, 1.0f, 0.1f)
        );
        private static final String FILE_NAME = ZomboidFileSystem.instance.getCacheDir() + File.separator + "RoomThermalSim.ini";

        static {
            OPTIONS.forEach(Option::reset);
        }

        public static void load() {
            ConfigFile file = new ConfigFile();
            if (!file.read(FILE_NAME)) {
                save();
                revision++;
                return;
            }
            for (ConfigOption configOption : file.getOptions()) {
                find(configOption.getName()).ifPresent(o -> o.set(configOption.getValueAsString()));
            }
            revision++;
        }

        public static void save() {
            ArrayList<ConfigOption> configOptions = new ArrayList<>();
            OPTIONS.forEach(option -> {
                ConfigOption configOption = option.toConfigOption();
                if (configOption != null) configOptions.add(configOption);
            });
            new ConfigFile().write(FILE_NAME, 0, configOptions);
        }

        public static void resetAll() {
            OPTIONS.forEach(Option::reset);
            revision++;
        }

        public static void edit(Option<?> option, Number value) {
            option.set(value);
            if (GameClient.client) {
                INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.RoomThermalConfig, RoomThermalConfigPacket.ACTION_SET, option.name(), option.get().doubleValue());
            }
        }

        public static void requestSave() {
            if (GameClient.client) {
                INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.RoomThermalConfig, RoomThermalConfigPacket.ACTION_SAVE);
            } else {
                save();
            }
        }

        public static void requestRestore() {
            if (GameClient.client) {
                INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.RoomThermalConfig, RoomThermalConfigPacket.ACTION_RESTORE);
            } else {
                load();
            }
        }

        public static void requestReset() {
            if (GameClient.client) {
                INetworkPacket.send(GameClient.connection, PacketTypes.PacketType.RoomThermalConfig, RoomThermalConfigPacket.ACTION_RESET);
            } else {
                resetAll();
            }
        }

        public static int getRevision() {
            return revision;
        }

        public static Optional<Option<? extends Number>> find(String name) {
            return OPTIONS.stream().filter(o -> o.name().equals(name)).findFirst();
        }

        public static void writeTo(ByteBufferWriter b) {
            b.putInt(OPTIONS.size());
            for (Option<? extends Number> option : OPTIONS) {
                b.putUTF(option.name());
                b.putDouble(option.get().doubleValue());
            }
        }

        public static void readFrom(ByteBufferReader b) {
            for (int i = 0, n = b.getInt(); i < n; i++) {
                String name = b.getUTF();
                double value = b.getDouble();
                find(name).ifPresent(o -> o.set(value));
            }
            revision++;
        }

        public record Option<T extends Number>(String name, String description, Type type, T min, T max, T defaultValue, Function<Number, T> converter, Field field) {

            public enum Type {
                GLOBAL,
                ROOM,
                ENVIRONMENT
            }

            public static Option<Integer> ofInteger(String name, String description, Type type, int min, int max, int defaultValue) {
                return new Option<>(name, description, type, min, max, defaultValue, Number::intValue, fieldFor(name, int.class));
            }

            public static Option<Float> ofFloat(String name, String description, Type type, float min, float max, float defaultValue) {
                return new Option<>(name, description, type, min, max, defaultValue, Number::floatValue, fieldFor(name, float.class));
            }

            public static Option<Double> ofDouble(String name, String description, Type type, double min, double max, double defaultValue) {
                return new Option<>(name, description, type, min, max, defaultValue, Number::doubleValue, fieldFor(name, double.class));
            }

            private static Field fieldFor(String name, Class<?> expectedType) {
                try {
                    Field field = ThermalConfig.class.getField(name);
                    if (field.getType() != expectedType || !Modifier.isStatic(field.getModifiers())) {
                        throw new IllegalStateException("ThermalConfig." + name + " must be a static " + expectedType);
                    }
                    return field;
                } catch (NoSuchFieldException e) {
                    throw new IllegalStateException("ThermalConfig has no field " + name, e);
                }
            }

            public T get() {
                try {
                    return this.converter.apply((Number) this.field.get(null));
                } catch (IllegalAccessException e) {
                    DebugType.General.printException(e, LogSeverity.Error);
                    return this.converter.apply(0);
                }
            }

            private void write(T value) {
                try {
                    this.field.set(null, value);
                } catch (IllegalAccessException e) {
                    DebugType.General.printException(e, LogSeverity.Error);
                }
            }

            public void reset() {
                this.write(this.defaultValue);
            }

            public void set(Number value) {
                double clamped = Math.clamp(value.doubleValue(), this.min.doubleValue(), this.max.doubleValue());

                this.write(this.converter.apply(clamped));
            }

            public void set(String string) {
                try {
                    this.set(Double.parseDouble(string));
                } catch (NumberFormatException e) {
                    DebugType.General.println("Malformed config option " + this.name + "=" + string + ", keeping " + this.get());
                }
            }

            @Nullable
            public ConfigOption toConfigOption() {
                if (this.defaultValue instanceof Integer) {
                    IntegerConfigOption configOption = new IntegerConfigOption(this.name, this.min.intValue(), this.max.intValue(), this.defaultValue.intValue()) {
                      @Override
                      public String getTooltip() {
                          return Option.this.fileComment();
                      }
                    };
                    configOption.setValue(this.get().intValue());
                    return configOption;
                }
                if (this.defaultValue instanceof Float || this.defaultValue instanceof Double) {
                    DoubleConfigOption configOption = new DoubleConfigOption(this.name, this.min.doubleValue(), this.max.doubleValue(), this.defaultValue.doubleValue()) {
                        @Override
                        public String getTooltip() {
                            return Option.this.fileComment();
                        }
                    };
                    configOption.setValue(this.get().doubleValue());
                    return configOption;
                }
                return null;
            }

            private String fileComment() {
                return this.description + "\nDefault: " + this.defaultValue;
            }
        }
    }

    private final class RoomTileScanWorker {

        private final ConcurrentLinkedQueue<IsoThermalRoom> jobQueue = new ConcurrentLinkedQueue<>();
        private volatile boolean finished;
        private Thread thread;

        void start() {
            this.finished = false;
            this.thread = new Thread(ThreadGroups.Workers, this::loop, "RoomTileScanWorker");
            this.thread.setDaemon(true);
            this.thread.setUncaughtExceptionHandler(GameWindow::uncaughtException);
            this.thread.start();
        }

        void stop() {
            this.finished = true;
            this.jobQueue.clear();
        }

        void enqueue(IsoThermalRoom room) {
            this.jobQueue.add(room);
        }

        private void loop() {
            while (!this.finished) {
                IsoThermalRoom room = this.jobQueue.poll();
                if (room == null) {
                    try {
                        Thread.sleep(20L);
                    } catch (InterruptedException ignored) {}
                    continue;
                }
                this.processRoom(room);
            }
        }

        private void processRoom(IsoThermalRoom room) {
            int generation = RoomTemperatureManager.this.regionGeneration;
            List<IsoGridSquare> newSquares = room.recomputeFloodfill();

            MainThread.invokeOnMainThread(() -> {
                this.rescanDone(room);
                if (!RoomTemperatureManager.this.simulatedRooms.contains(room)) return;
                if (generation != RoomTemperatureManager.this.regionGeneration) {
                    // Regions were swapped mid-scan, so the result may mix old and new region data
                    RoomTemperatureManager.this.pendingRescanRooms.add(room);
                } else if (newSquares.isEmpty() || RoomTemperatureManager.this.isRegionStale(room)) {
                    RoomTemperatureManager.this.evictStaleRoom(room);
                } else if (RoomTemperatureManager.this.simulatedRooms.stream().anyMatch(r -> r != room && r.isPlayerRoom() && r.containsSquare(newSquares.get(0)))) {
                    RoomTemperatureManager.this.evictSimulatedRoom(room); // regions merged, another room already covers this one
                } else if (room.applyRescannedSquares(newSquares)) {
                    RoomTemperatureManager.this.invalidateSyncedRoom(room.getId());
                }
            });
        }

        private void rescanDone(IsoThermalRoom room) {
            RoomTemperatureManager.this.rescanInProgress.remove(room.getId());
        }

    }

}