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

/**
 * Server -> client: full config (ACTION_SYNC).
 * Client -> server: ACTION_SET (one option), ACTION_SAVE, ACTION_RESTORE, ACTION_RESET.
 */
@PacketSetting(ordering = 15, priority = 1, reliability = 2, requiredCapability = Capability.ConnectWithDebug, handlingType = 3)
public class RoomThermalConfigPacket implements INetworkPacket {

    public static final byte ACTION_SYNC = 0, ACTION_SET = 1, ACTION_SAVE = 2, ACTION_RESTORE = 3, ACTION_RESET = 4;

    private byte action;
    private String name;
    private double value;

    @Override
    public void setData(Object... values) {
        if (values.length == 1 && values[0] instanceof Byte a) {
            this.action = a;
        } else if (values.length == 3 && values[0] instanceof Byte a && values[1] instanceof String n && values[2] instanceof Double v) {
            this.action = a;
            this.name = n;
            this.value = v;
        } else {
            DebugType.Multiplayer.warn("%s setData() got invalid arguments", this.getClass().getSimpleName());
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putByte(this.action);
        if (this.action == ACTION_SYNC) {
            RoomTemperatureManager.ThermalConfig.writeTo(b);
        } else if (this.action == ACTION_SET) {
            b.putUTF(this.name);
            b.putDouble(this.value);
        }
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        byte action = b.getByte();
        if (GameServer.server) {
            switch (action) {
                case ACTION_SET -> {
                    String name = b.getUTF();
                    double value = b.getDouble();
                    RoomTemperatureManager.ThermalConfig.find(name).ifPresent(o -> o.set(value));
                    INetworkPacket.sendToAll(PacketTypes.PacketType.RoomThermalConfig, connection, ACTION_SYNC);
                }
                case ACTION_SAVE -> RoomTemperatureManager.ThermalConfig.save();
                case ACTION_RESTORE -> {
                    RoomTemperatureManager.ThermalConfig.load();
                    INetworkPacket.sendToAll(PacketTypes.PacketType.RoomThermalConfig, ACTION_SYNC);
                }
                case ACTION_RESET -> {
                    RoomTemperatureManager.ThermalConfig.resetAll();
                    INetworkPacket.sendToAll(PacketTypes.PacketType.RoomThermalConfig, ACTION_SYNC);
                }
            }
        } else if (action == ACTION_SYNC) {
            RoomTemperatureManager.ThermalConfig.readFrom(b);
        }
    }

}
