package zombie.network.packets;

import zombie.characters.Capability;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.iso.IsoThermalRoom;
import zombie.iso.weather.RoomTemperatureManager;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketSetting;
import zombie.network.PacketTypes;

/** Client -> server: room id + action
 * Server -> client: room id + {@link zombie.iso.IsoThermalRoom.DebugInfo} payload
 */
@PacketSetting(ordering = 15, priority = 1, reliability = 2, requiredCapability = Capability.ConnectWithDebug, handlingType = 3)
public class RoomThermalDebugPacket implements INetworkPacket {

    public static final byte ACTION_VIEW = 0, ACTION_RESCAN = 1, ACTION_SET_TEMP = 2, ACTION_FORECAST = 3;

    private long roomId;
    private byte action;
    private float value;
    private IsoThermalRoom.DebugInfo debugInfo;
    private boolean withForecast;

    @Override
    public void setData(Object... values) {
        if (values.length == 3 && values[0] instanceof Long id && values[1] instanceof Byte a && values[2] instanceof Float v) {
            this.roomId = id;
            this.action = a;
            this.value = v;
        } else if (values.length == 2 && values[0] instanceof IsoThermalRoom room && values[1] instanceof Boolean forecast) {
            this.roomId = room.getId();
            this.debugInfo = room.enableDebugInfo();
            this.withForecast = forecast;
        } else {
            DebugType.Multiplayer.warn("%s setData() got invalid arguments", this.getClass().getSimpleName());
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putLong(this.roomId);
        if (GameServer.server) {
            this.debugInfo.writeTo(b, this.withForecast);
        } else {
            b.putByte(this.action);
            b.putFloat(this.value);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        long id = b.getLong();
        if (GameServer.server) {
            byte action = b.getByte();
            float value = b.getFloat();
            RoomTemperatureManager.getInstance().getSimulatedRoomById(id).ifPresent(room -> {
                room.enableDebugInfo().runAction(action, value);
                INetworkPacket.send(connection, PacketTypes.PacketType.RoomThermalDebug, room, action == ACTION_FORECAST);
            });
        } else {
            RoomTemperatureManager.getInstance().getSimulatedRoomById(id)
                    .ifPresent(room -> room.getDebugInfo().readFrom(b));
        }
    }

}
