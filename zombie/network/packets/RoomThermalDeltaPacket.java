package zombie.network.packets;

import zombie.characters.Capability;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.iso.weather.RoomTemperatureManager;
import zombie.network.IConnection;
import zombie.network.PacketSetting;

import java.util.ArrayList;
import java.util.List;

@PacketSetting(ordering = 15, priority = 1, reliability = 1, requiredCapability = Capability.ClimateManager, handlingType = 2)
public class RoomThermalDeltaPacket  implements INetworkPacket {
    private List<RoomThermalDeltaEntry> rooms = new ArrayList<>();

    @Override
    public void setData(Object... values) {
        if (values.length == 1 && values[0] instanceof List<?> list) {
            this.rooms = (List<RoomThermalDeltaEntry>) list;
        } else {
            DebugType.Multiplayer.warn("%s setData() got invalid arguments", this.getClass().getSimpleName());
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putInt(rooms.size());
        for (RoomThermalDeltaEntry r : rooms) {
            b.putLong(r.id());
            b.putFloat(r.currentTemp());
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        int count = b.getInt();
        for (int i = 0; i < count; i++) {
            RoomTemperatureManager.getInstance().applyDeltaFromServer(b.getLong(), b.getFloat());
        }
    }

    public record RoomThermalDeltaEntry(long id, float currentTemp) {}
}
