package zombie.network.packets;

import zombie.characters.Capability;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.iso.weather.RoomTemperatureManager;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

import java.util.ArrayList;
import java.util.List;

@PacketSetting(ordering = 9, priority = 1, reliability = 2, requiredCapability = Capability.ConnectWithDebug, handlingType = 3)
public class RoomThermalPersistencePacket implements INetworkPacket {

    public static final byte ACTION_VIEW = 0, ACTION_CLEANUP = 1;
    private static final int MAX_ENTRIES = 20000;

    private byte action;

    @Override
    public void setData(Object... values) {
        if (values.length == 1 && values[0] instanceof Byte a) {
            this.action = a;
        } else if (values.length != 0) {
            DebugType.Multiplayer.warn("%s setData() got invalid arguments", this.getClass().getSimpleName());
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        if (GameServer.server) {
            List<RoomTemperatureManager.PersistentThermalData> data = RoomTemperatureManager.getInstance().getPersistentThermalData();
            int count = Math.min(data.size(), MAX_ENTRIES);
            b.putInt(count);
            for (int i = 0; i < count; i++) data.get(i).write(b);
        } else {
            b.putByte(this.action);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        if (GameServer.server) {
            if (b.getByte() == ACTION_CLEANUP) {
                RoomTemperatureManager.getInstance().clearPersistentThermalData();
            }
            INetworkPacket.send(connection, PacketTypes.PacketType.RoomThermalPersistence);
        } else {
            int count = b.getInt();
            List<RoomTemperatureManager.PersistentThermalData> data = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                data.add(RoomTemperatureManager.PersistentThermalData.read(b));
            }
            RoomTemperatureManager.getInstance().applyPersistentThermalDataFromServer(data);
        }
    }

}
