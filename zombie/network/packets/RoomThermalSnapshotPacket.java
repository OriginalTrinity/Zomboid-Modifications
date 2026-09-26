package zombie.network.packets;

import zombie.characters.Capability;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.iso.IsoThermalRoom;
import zombie.iso.weather.RoomTemperatureManager;
import zombie.network.IConnection;
import zombie.network.PacketSetting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@PacketSetting(ordering = 9, priority = 1, reliability = 3, requiredCapability = Capability.ClimateManager, handlingType = 2)
public class RoomThermalSnapshotPacket implements INetworkPacket {
    private List<RoomThermalStateSnapshot> rooms = new ArrayList<>();

    @Override
    public void setData(Object... values) {
        if (values.length == 1 && values[0] instanceof List<?> list) {
            this.rooms = (List<RoomThermalStateSnapshot>) list;
        } else {
            DebugType.Multiplayer.warn("%s setData() got invalid arguments", this.getClass().getSimpleName());
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putInt(rooms.size());
        for (RoomThermalStateSnapshot r : rooms) {
            b.putLong(r.id());
            b.putInt(r.x());
            b.putInt(r.y());
            b.putInt(r.z());
            b.putBoolean(r.isPlayerRoom());
            b.putFloat(r.currentTemp());
            if (r.isPlayerRoom()) writeTiles(b, r.squareHashes());
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        int count = b.getInt();
        for (int i = 0; i < count; i++) {
            long id = b.getLong();
            int x = b.getInt();
            int y = b.getInt();
            int z = b.getInt();
            boolean isPlayerRoom = b.getBoolean();
            float temp = b.getFloat();
            Set<Long> squareHashes = isPlayerRoom ? readTiles(b, z) : null;
            RoomTemperatureManager.getInstance().applySnapshotFromServer(id, x, y, z, isPlayerRoom, temp, squareHashes);
        }
    }

    public static void writeTiles(ByteBufferWriter b, Set<Long> squareHashes) {
        b.putInt(squareHashes.size());
        if (squareHashes.isEmpty()) return;
        int originX = Integer.MAX_VALUE, originY = Integer.MAX_VALUE;
        for (long hash : squareHashes) {
            originX = Math.min(originX, IsoThermalRoom.unpackX(hash));
            originY = Math.min(originY, IsoThermalRoom.unpackY(hash));
        }
        b.putInt(originX);
        b.putInt(originY);
        for (long hash : squareHashes) {
            b.putShort((short) (IsoThermalRoom.unpackX(hash) - originX));
            b.putShort((short) (IsoThermalRoom.unpackY(hash) - originY));
        }
    }

    public static Set<Long> readTiles(ByteBufferReader b, int z) {
        int tileCount = b.getInt();
        HashSet<Long> squareHashes = new HashSet<>(tileCount * 2);
        if (tileCount == 0) return squareHashes;
        int originX = b.getInt();
        int originY = b.getInt();
        for (int i = 0; i < tileCount; i++) {
            int dx = b.getShort() & 0xFFFF;
            int dy = b.getShort() & 0xFFFF;
            squareHashes.add(IsoThermalRoom.packCoordinates(originX + dx, originY + dy, z));
        }
        return squareHashes;
    }

    public record RoomThermalStateSnapshot(long id, int x, int y, int z, boolean isPlayerRoom, float currentTemp, Set<Long> squareHashes) {}
}
