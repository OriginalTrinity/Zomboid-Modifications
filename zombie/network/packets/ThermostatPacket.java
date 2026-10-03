package zombie.network.packets;

import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.iso.objects.IsoLightSwitch;
import zombie.iso.weather.RoomTemperatureManager;
import zombie.network.IConnection;
import zombie.network.PacketSetting;

@PacketSetting(ordering = 15, priority = 1, reliability = 2, requiredCapability = Capability.None, handlingType = 1)
public class ThermostatPacket implements INetworkPacket {
    private byte playerIndex;
    private int x, y, z;
    private boolean on;
    private float setPoint;

    @Override
    public void setData(Object... values) {
        if (values.length == 4
                && values[0] instanceof IsoPlayer player
                && values[1] instanceof IsoLightSwitch lightSwitch
                && values[2] instanceof Boolean on
                && values[3] instanceof Float setPoint) {
            this.playerIndex = (byte) player.getPlayerNum();
            this.x = lightSwitch.getSquare().getX();
            this.y = lightSwitch.getSquare().getY();
            this.z = lightSwitch.getSquare().getZ();
            this.on = on;
            this.setPoint = setPoint;
        } else {
            DebugType.Multiplayer.warn("%s setData() got invalid arguments", this.getClass().getSimpleName());
        }
    }

    @Override
    public void write(ByteBufferWriter b) {
        b.putByte(this.playerIndex);
        b.putInt(this.x);
        b.putInt(this.y);
        b.putInt(this.z);
        b.putBoolean(this.on);
        b.putFloat(this.setPoint);
    }

    @Override
    public void parse(ByteBufferReader b, IConnection connection) {
        byte index = b.getByte();
        int x = b.getInt(), y = b.getInt(), z = b.getInt();
        boolean on = b.getBoolean();
        float setPoint = b.getFloat();
        if (!(connection instanceof UdpConnection udp) || index < 0 || index >= udp.players.length) return;
        RoomTemperatureManager.getInstance().handleThermostatRequest(udp.players[index], x, y, z, on, setPoint);
    }
}
