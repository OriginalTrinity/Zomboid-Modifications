package zombie.iso.weather;

import org.apache.commons.lang3.tuple.Triple;
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
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.RoomThermalDeltaPacket;
import zombie.network.packets.RoomThermalSnapshotPacket;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class RoomTemperatureManager {

    private static RoomTemperatureManager instance;

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

    private double lastApplyWorldHours = -1;
    private double lastCalculateWorldHours = -1;
    private double lastCleanUpMillis = -1;
    private float groundTemperature = ThermalConfig.GROUND_TEMPERATURE;

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
        this.tileScanWorker = new RoomTileScanWorker();
        this.tileScanWorker.start();
        this.pendingPlayerRoomChunks = new HashSet<>();
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
            IsoGridSquare changed;
            while ((changed = this.pendingSquareChanges.poll()) != null) {
                this.processSquareChanged(changed);
            }
            if (!this.pendingRescanRooms.isEmpty()) {
                Set<IsoThermalRoom> toRescan = new HashSet<>(this.pendingRescanRooms);
                this.pendingRescanRooms.removeAll(toRescan);
                for (IsoThermalRoom room : toRescan) {
                    if (this.isRegionStale(room)) {
                        this.evictSimulatedRoom(room);
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
                if (this.isRegionStale(existing.get())) this.evictSimulatedRoom(existing.get());
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
        int squareCount = Math.max(room.getSquares().size(), 1);

        Triple<Double, Float, Float> oldest = this.outdoorHistory.peekFirst();
        if (oldest != null && cursor < oldest.getLeft()) {
            float[] avg = this.averageOutdoorHistory();
            room.calculateTargetTemperature(avg[0], avg[1]);
            room.stepTemperature(oldest.getLeft() - cursor, squareCount);
            cursor = oldest.getLeft();
        }
        for (Triple<Double, Float, Float> sample : this.outdoorHistory) {
            if (sample.getLeft() <= cursor) continue;
            room.calculateTargetTemperature(sample.getMiddle(), sample.getRight());
            room.stepTemperature(sample.getLeft() - cursor, squareCount);
            cursor = sample.getLeft();
        }

        room.calculateTargetTemperature();
        room.stepTemperature(now - cursor, squareCount);
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

    private void savePersistentThermalData(List<PersistentThermalData> staleRooms) {
        File outFile = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("thermalSim.bin"));
        FileOutputStream outputStream;
        try {
            outputStream = new FileOutputStream(outFile);
        } catch (FileNotFoundException e) {
            DebugType.General.printException(e, LogSeverity.Error);
            return;
        }

        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(outputStream));
        try {
            output.writeByte(82); // R
            output.writeByte(77); // M
            output.writeByte(84); // T
            output.writeByte(77); // M
            output.writeShort(2); // Version number
            output.writeInt(staleRooms.size());
            staleRooms.forEach(ptd -> this.savePersistentThermalData(output, ptd));
            this.saveOutdoorTemperatureHistory(output);
            output.flush();
            output.close();
        } catch (IOException e) {
            DebugType.General.printException(e, LogSeverity.Error);
        }
    }

    private void savePersistentThermalData(DataOutputStream output, PersistentThermalData ptd) {
        try {
            output.writeInt(ptd.x());
            output.writeInt(ptd.y());
            output.writeInt(ptd.z());
            output.writeFloat(ptd.lastTemp());
            output.writeDouble(ptd.lastUpdate());
            output.writeBoolean(ptd.isPlayerRoom());
        } catch (IOException e) {
            DebugType.General.printException(e, LogSeverity.Error);
        }
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
        File outFile = new File(ZomboidFileSystem.instance.getFileNameInCurrentSave("thermalSim.bin"));
        FileInputStream inputStream;
        try {
            inputStream = new FileInputStream(outFile);
        } catch (FileNotFoundException e) {
            DebugType.General.printException(e, LogSeverity.Error);
            return;
        }

        DataInputStream input = new DataInputStream(new BufferedInputStream(inputStream));
        try {
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
            input.close();
        } catch (IOException e) {
            DebugType.General.printException(e, LogSeverity.Error);
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

    public record PersistentThermalData(int x, int y, int z, float lastTemp, double lastUpdate, boolean isPlayerRoom) {

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
        // RoomTemperatureManager constants
        public static double PLAYER_ROOM_STALE_MATCH_RADIUS = 32.0;
        public static double ROOM_SYNC_RELEVANCE_RADIUS = 80.0;
        public static float TEMP_SYNC_EPSILON = 0.05f;
        public static int ROOM_REQUEST_COOLDOWN = 5000;
        public static double OUTDOOR_SAMPLE_INTERVAL_HOURS = 1.0;
        public static double OUTDOOR_HISTORY_MAX_HOURS = 72.0;
        public static double TEMP_APPLY_INTERVAL_HOURS = 0.02;   // ~1 game-minute
        public static double TEMP_CALCULATE_INTERVAL_HOURS = 0.1; // ~6 game-minutes
        public static double PERSISTENT_THERMAL_DATA_MAX_AGE = 720.0;

        // IsoThermalRoom constants
        public static float BASE_COEFFICIENT = 0.05f;
        public static float WINDOW_CLOSED_COEFFICIENT = 0.03f;
        public static float WINDOW_CURTAIN_MULTIPLIER = 0.6f;
        public static float WINDOW_OPEN_COEFFICIENT = 0.5f;
        public static float BARRICADE_INSULATION_MULTIPLIER = 1.0f;
        public static float DOOR_CLOSED_COEFFICIENT = 0.02f;
        public static float DOOR_OPEN_COEFFICIENT = 0.8f;
        public static float BREACH_COEFFICIENT = 1.0f;
        public static float STAIR_LINK_COEFFICIENT = 3.0f;

        public static float HEATSOURCE_MIN_COEFFICIENT = 0.05f;
        public static float HEATSOURCE_MAX_COEFFICIENT = 10.0f;
        public static float HEATSOURCE_RADIUS_SCALE = 1.0f;
        public static int HEATSOURCE_PROXIMITY_RADIUS = 3;
        public static float HEATSOURCE_PROXIMITY_STRENGTH = 0.5f;
        public static float CLIMATE_CONTROL_COEFFICIENT = 8.0f;

        public static float TEMP_CHANGE_RATE_MULTIPLIER = 20.0f;
        public static float INTERROOM_TRANSFER_MULTIPLIER = 5.0f;
        public static float HEATING_RATE_MULTIPLIER = 1.0f;
        public static float COOLING_RATE_MULTIPLIER = 1.0f;
        public static float MAX_TEMP_DELTA = 10.0f;

        public static float UPPER_FLOOR_TEMP_DROP = 1.0f; // °C colder outdoor air per z-level > 0
        public static float SOLAR_ROOF_GAIN = 8.0f; // °C added to a roof-exposed room's outdoor air at full sun
        public static float GROUND_TEMPERATURE = 10.0f; // neutral ground temperature
        public static float GROUND_OUTDOOR_FACTOR = 0.4f;
        public static float BASEMENT_GROUND_COEFFICIENT = 5.0f;
        public static float BASEMENT_OUTDOOR_FACTOR = 0.2f;
        public static float MIN_FLOOR_OUTDOOR_MULTIPLIER = 0.1f;

        private static final String FILE_NAME = ZomboidFileSystem.instance.getCacheDir() + File.separator + "RoomThermalSim.ini";

        public static void load() {
            ConfigFile file = new ConfigFile();
            if (!file.read(FILE_NAME)) {
                save();
                return;
            }
            for (ConfigOption option : file.getOptions()) {
                switch (option.getName()) {
                    case "PLAYER_ROOM_STALE_MATCH_RADIUS" -> PLAYER_ROOM_STALE_MATCH_RADIUS = Float.parseFloat(option.getValueAsString());
                    case "ROOM_SYNC_RELEVANCE_RADIUS" -> ROOM_SYNC_RELEVANCE_RADIUS = Float.parseFloat(option.getValueAsString());
                    case "TEMP_SYNC_EPSILON" -> TEMP_SYNC_EPSILON = Float.parseFloat(option.getValueAsString());
                    case "ROOM_REQUEST_COOLDOWN" -> ROOM_REQUEST_COOLDOWN = Integer.parseInt(option.getValueAsString());
                    case "OUTDOOR_SAMPLE_INTERVAL_HOURS" -> OUTDOOR_SAMPLE_INTERVAL_HOURS = Float.parseFloat(option.getValueAsString());
                    case "OUTDOOR_HISTORY_MAX_HOURS" -> OUTDOOR_HISTORY_MAX_HOURS = Float.parseFloat(option.getValueAsString());
                    case "TEMP_APPLY_INTERVAL_HOURS" -> TEMP_APPLY_INTERVAL_HOURS = Float.parseFloat(option.getValueAsString());
                    case "TEMP_CALCULATE_INTERVAL_HOURS" -> TEMP_CALCULATE_INTERVAL_HOURS = Float.parseFloat(option.getValueAsString());
                    case "PERSISTENT_THERMAL_DATA_MAX_AGE" -> PERSISTENT_THERMAL_DATA_MAX_AGE = Float.parseFloat(option.getValueAsString());
                    case "BASE_COEFFICIENT" -> BASE_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "WINDOW_CLOSED_COEFFICIENT" -> WINDOW_CLOSED_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "WINDOW_CURTAIN_MULTIPLIER" -> WINDOW_CURTAIN_MULTIPLIER = Float.parseFloat(option.getValueAsString());
                    case "WINDOW_OPEN_COEFFICIENT" -> WINDOW_OPEN_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "BARRICADE_INSULATION_MULTIPLIER" -> BARRICADE_INSULATION_MULTIPLIER = Float.parseFloat(option.getValueAsString());
                    case "DOOR_CLOSED_COEFFICIENT" -> DOOR_CLOSED_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "DOOR_OPEN_COEFFICIENT" -> DOOR_OPEN_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "BREACH_COEFFICIENT" -> BREACH_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "STAIR_LINK_COEFFICIENT" -> STAIR_LINK_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "HEATSOURCE_MIN_COEFFICIENT" -> HEATSOURCE_MIN_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "HEATSOURCE_MAX_COEFFICIENT" -> HEATSOURCE_MAX_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "HEATSOURCE_RADIUS_SCALE" -> HEATSOURCE_RADIUS_SCALE = Float.parseFloat(option.getValueAsString());
                    case "HEATSOURCE_PROXIMITY_RADIUS" -> HEATSOURCE_PROXIMITY_RADIUS = Integer.parseInt(option.getValueAsString());
                    case "HEATSOURCE_PROXIMITY_STRENGTH" -> HEATSOURCE_PROXIMITY_STRENGTH = Float.parseFloat(option.getValueAsString());
                    case "CLIMATE_CONTROL_COEFFICIENT" -> CLIMATE_CONTROL_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "TEMP_CHANGE_RATE_MULTIPLIER" -> TEMP_CHANGE_RATE_MULTIPLIER = Float.parseFloat(option.getValueAsString());
                    case "INTERROOM_TRANSFER_MULTIPLIER" -> INTERROOM_TRANSFER_MULTIPLIER = Float.parseFloat(option.getValueAsString());
                    case "HEATING_RATE_MULTIPLIER" -> HEATING_RATE_MULTIPLIER = Float.parseFloat(option.getValueAsString());
                    case "COOLING_RATE_MULTIPLIER" -> COOLING_RATE_MULTIPLIER = Float.parseFloat(option.getValueAsString());
                    case "MAX_TEMP_DELTA" -> MAX_TEMP_DELTA = Float.parseFloat(option.getValueAsString());
                    case "UPPER_FLOOR_TEMP_DROP" -> UPPER_FLOOR_TEMP_DROP = Float.parseFloat(option.getValueAsString());
                    case "SOLAR_ROOF_GAIN" -> SOLAR_ROOF_GAIN = Float.parseFloat(option.getValueAsString());
                    case "GROUND_TEMPERATURE" -> GROUND_TEMPERATURE = Float.parseFloat(option.getValueAsString());
                    case "GROUND_OUTDOOR_FACTOR" -> GROUND_OUTDOOR_FACTOR = Float.parseFloat(option.getValueAsString());
                    case "BASEMENT_GROUND_COEFFICIENT" -> BASEMENT_GROUND_COEFFICIENT = Float.parseFloat(option.getValueAsString());
                    case "BASEMENT_OUTDOOR_FACTOR" -> BASEMENT_OUTDOOR_FACTOR = Float.parseFloat(option.getValueAsString());
                    case "MIN_FLOOR_OUTDOOR_MULTIPLIER" -> MIN_FLOOR_OUTDOOR_MULTIPLIER = Float.parseFloat(option.getValueAsString());
                }
            }
        }

        public static void save() {
            ConfigFile configFile = new ConfigFile();
            ArrayList<ConfigOption> configOptions = new ArrayList<>();

            ConfigOption option = new DoubleConfigOption("PLAYER_ROOM_STALE_MATCH_RADIUS", 1, 128, 32);
            ((DoubleConfigOption)option).setValue(PLAYER_ROOM_STALE_MATCH_RADIUS);
            configOptions.add(option);
            option = new DoubleConfigOption("ROOM_SYNC_RELEVANCE_RADIUS", 1, 128, 80);
            ((DoubleConfigOption)option).setValue(ROOM_SYNC_RELEVANCE_RADIUS);
            configOptions.add(option);
            option = new DoubleConfigOption("TEMP_SYNC_EPSILON", 0.01, Double.MAX_VALUE, 0.05);
            ((DoubleConfigOption)option).setValue(TEMP_SYNC_EPSILON);
            configOptions.add(option);
            option = new IntegerConfigOption("ROOM_REQUEST_COOLDOWN", 0, Integer.MAX_VALUE, 5000);
            ((IntegerConfigOption)option).setValue(ROOM_REQUEST_COOLDOWN);
            configOptions.add(option);
            option = new DoubleConfigOption("OUTDOOR_SAMPLE_INTERVAL_HOURS", 0.01, Double.MAX_VALUE, 1);
            ((DoubleConfigOption)option).setValue(OUTDOOR_SAMPLE_INTERVAL_HOURS);
            configOptions.add(option);
            option = new DoubleConfigOption("OUTDOOR_HISTORY_MAX_HOURS", 1, Double.MAX_VALUE, 72);
            ((DoubleConfigOption)option).setValue(OUTDOOR_HISTORY_MAX_HOURS);
            configOptions.add(option);
            option = new DoubleConfigOption("TEMP_APPLY_INTERVAL_HOURS", 0, Double.MAX_VALUE, 0.02);
            ((DoubleConfigOption)option).setValue(TEMP_APPLY_INTERVAL_HOURS);
            configOptions.add(option);
            option = new DoubleConfigOption("TEMP_CALCULATE_INTERVAL_HOURS", 0, Double.MAX_VALUE, 0.1);
            ((DoubleConfigOption)option).setValue(TEMP_CALCULATE_INTERVAL_HOURS);
            configOptions.add(option);
            option = new DoubleConfigOption("PERSISTENT_THERMAL_DATA_MAX_AGE", 0, Double.MAX_VALUE, 720.0);
            ((DoubleConfigOption)option).setValue(PERSISTENT_THERMAL_DATA_MAX_AGE);
            configOptions.add(option);
            option = new DoubleConfigOption("BASE_COEFFICIENT", 0, Double.MAX_VALUE, 0.05);
            ((DoubleConfigOption)option).setValue(BASE_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("WINDOW_CLOSED_COEFFICIENT", 0, Double.MAX_VALUE, 0.03);
            ((DoubleConfigOption)option).setValue(WINDOW_CLOSED_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("WINDOW_CURTAIN_MULTIPLIER", 0, Double.MAX_VALUE, 0.6);
            ((DoubleConfigOption)option).setValue(WINDOW_CURTAIN_MULTIPLIER);
            configOptions.add(option);
            option = new DoubleConfigOption("WINDOW_OPEN_COEFFICIENT", 0, Double.MAX_VALUE, 0.5);
            ((DoubleConfigOption)option).setValue(WINDOW_OPEN_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("BARRICADE_INSULATION_MULTIPLIER", 0, Double.MAX_VALUE, 1.0);
            ((DoubleConfigOption)option).setValue(BARRICADE_INSULATION_MULTIPLIER);
            configOptions.add(option);
            option = new DoubleConfigOption("DOOR_CLOSED_COEFFICIENT", 0, Double.MAX_VALUE, 0.02);
            ((DoubleConfigOption)option).setValue(DOOR_CLOSED_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("DOOR_OPEN_COEFFICIENT", 0, Double.MAX_VALUE, 0.8);
            ((DoubleConfigOption)option).setValue(DOOR_OPEN_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("BREACH_COEFFICIENT", 0, Double.MAX_VALUE, 1.0);
            ((DoubleConfigOption)option).setValue(BREACH_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("STAIR_LINK_COEFFICIENT", 0, Double.MAX_VALUE, 3.0);
            ((DoubleConfigOption)option).setValue(STAIR_LINK_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("HEATSOURCE_MIN_COEFFICIENT", 0, Double.MAX_VALUE, 0.05);
            ((DoubleConfigOption)option).setValue(HEATSOURCE_MIN_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("HEATSOURCE_MAX_COEFFICIENT", 0, Double.MAX_VALUE, 10.0);
            ((DoubleConfigOption)option).setValue(HEATSOURCE_MAX_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("HEATSOURCE_RADIUS_SCALE", 0, Double.MAX_VALUE, 1.0);
            ((DoubleConfigOption)option).setValue(HEATSOURCE_RADIUS_SCALE);
            configOptions.add(option);
            option = new IntegerConfigOption("HEATSOURCE_PROXIMITY_RADIUS", 0, 128, 3);
            ((IntegerConfigOption)option).setValue(HEATSOURCE_PROXIMITY_RADIUS);
            configOptions.add(option);
            option = new DoubleConfigOption("HEATSOURCE_PROXIMITY_STRENGTH", 0, Double.MAX_VALUE, 0.5);
            ((DoubleConfigOption)option).setValue(HEATSOURCE_PROXIMITY_STRENGTH);
            configOptions.add(option);
            option = new DoubleConfigOption("CLIMATE_CONTROL_COEFFICIENT", 0, Double.MAX_VALUE, 8.0);
            ((DoubleConfigOption)option).setValue(CLIMATE_CONTROL_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("TEMP_CHANGE_RATE_MULTIPLIER", 0, Double.MAX_VALUE, 20.0);
            ((DoubleConfigOption)option).setValue(TEMP_CHANGE_RATE_MULTIPLIER);
            configOptions.add(option);
            option = new DoubleConfigOption("INTERROOM_TRANSFER_MULTIPLIER", 0, Double.MAX_VALUE, 5.0);
            ((DoubleConfigOption)option).setValue(INTERROOM_TRANSFER_MULTIPLIER);
            configOptions.add(option);
            option = new DoubleConfigOption("HEATING_RATE_MULTIPLIER", 0, Double.MAX_VALUE, 1.0);
            ((DoubleConfigOption)option).setValue(HEATING_RATE_MULTIPLIER);
            configOptions.add(option);
            option = new DoubleConfigOption("COOLING_RATE_MULTIPLIER", 0, Double.MAX_VALUE, 1.0);
            ((DoubleConfigOption)option).setValue(COOLING_RATE_MULTIPLIER);
            configOptions.add(option);
            option = new DoubleConfigOption("MAX_TEMP_DELTA", 0, Double.MAX_VALUE, 10.0);
            ((DoubleConfigOption)option).setValue(MAX_TEMP_DELTA);
            configOptions.add(option);
            option = new DoubleConfigOption("UPPER_FLOOR_TEMP_DROP", 0, Double.MAX_VALUE, 1.0);
            ((DoubleConfigOption)option).setValue(UPPER_FLOOR_TEMP_DROP);
            configOptions.add(option);
            option = new DoubleConfigOption("SOLAR_ROOF_GAIN", 0, Double.MAX_VALUE, 8.0);
            ((DoubleConfigOption)option).setValue(SOLAR_ROOF_GAIN);
            configOptions.add(option);
            option = new DoubleConfigOption("GROUND_TEMPERATURE", -Double.MAX_VALUE, Double.MAX_VALUE, 10.0);
            ((DoubleConfigOption)option).setValue(GROUND_TEMPERATURE);
            configOptions.add(option);
            option = new DoubleConfigOption("GROUND_OUTDOOR_FACTOR", 0, 1, 0.4);
            ((DoubleConfigOption)option).setValue(GROUND_OUTDOOR_FACTOR);
            configOptions.add(option);
            option = new DoubleConfigOption("BASEMENT_GROUND_COEFFICIENT", 0, Double.MAX_VALUE, 5.0);
            ((DoubleConfigOption)option).setValue(BASEMENT_GROUND_COEFFICIENT);
            configOptions.add(option);
            option = new DoubleConfigOption("BASEMENT_OUTDOOR_FACTOR", 0, 1, 0.2);
            ((DoubleConfigOption)option).setValue(BASEMENT_OUTDOOR_FACTOR);
            configOptions.add(option);
            option = new DoubleConfigOption("MIN_FLOOR_OUTDOOR_MULTIPLIER", 0, 1, 0.1);
            ((DoubleConfigOption)option).setValue(MIN_FLOOR_OUTDOOR_MULTIPLIER);
            configOptions.add(option);

            configFile.write(FILE_NAME, 0, configOptions);
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
            if (RoomTemperatureManager.this.isRegionStale(room)) {
                MainThread.invokeOnMainThread(() -> RoomTemperatureManager.this.evictSimulatedRoom(room));
                this.rescanDone(room);
                return;
            }

            List<IsoGridSquare> newSquares = room.recomputeFloodfill();

            MainThread.invokeOnMainThread(() -> {
                if (RoomTemperatureManager.this.isRegionStale(room)) {
                    RoomTemperatureManager.this.evictSimulatedRoom(room);
                } else if (!newSquares.isEmpty() && RoomTemperatureManager.this.simulatedRooms.stream()
                        .anyMatch(r -> r != room && r.isPlayerRoom() && r.containsSquare(newSquares.get(0)))) {
                    RoomTemperatureManager.this.evictSimulatedRoom(room); // regions merged, another room already covers this one
                } else if (room.applyRescannedSquares(newSquares)) {
                    RoomTemperatureManager.this.invalidateSyncedRoom(room.getId());
                }
                this.rescanDone(room);
            });
        }

        private void rescanDone(IsoThermalRoom room) {
            RoomTemperatureManager.this.rescanInProgress.remove(room.getId());
        }

    }

}