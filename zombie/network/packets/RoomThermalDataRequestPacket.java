package zombie.network.packets;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.iso.weather.RoomTemperatureManager;
import zombie.network.IConnection;
import zombie.network.PacketSetting;

import java.util.ArrayList;
import java.util.List;

@PacketSetting(ordering = 9, priority = 1, reliability = 2, requiredCapability = Capability.None, handlingType = 1)
public class RoomThermalDataRequestPacket implements INetworkPacket {
    private List<Long> roomIds = new ArrayList<>();

    @Override
    public void setData(Object... values) {
        if (values.length == 1 && values[0] instanceof List<?> list) {
            this.roomIds = (List<Long>) list;
        } else {
            DebugType.Multiplayer.warn("%s setData() got invalid arguments", this.getClass().getSimpleName());
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putInt(roomIds.size());
        for (long id : roomIds) b.putLong(id);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        int count = b.getInt();
        List<Long> requested = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            requested.add(b.getLong());
        }

        if (connection instanceof UdpConnection udp) {
            for (IsoPlayer player : udp.players) {
                if (player != null && player.onlineId != -1) {
                    RoomTemperatureManager.getInstance().handleRoomThermalDataRequest(player, requested);
                }
            }
        }
    }

}