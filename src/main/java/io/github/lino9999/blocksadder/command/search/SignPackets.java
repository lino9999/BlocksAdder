package io.github.lino9999.blocksadder.command.search;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.Converters;
import java.util.List;
import org.bukkit.block.sign.Side;

final class SignPackets {
    static final PacketType OPEN = PacketType.Play.Server.OPEN_SIGN_EDITOR;
    static final PacketType UPDATE = PacketType.Play.Client.UPDATE_SIGN;
    private final ProtocolManager manager;
    private final boolean booleanSide;

    SignPackets(ProtocolManager manager) {
        this.manager = manager;
        if (!OPEN.isSupported() || !UPDATE.isSupported()) throw new IllegalStateException("The server does not support sign editor packets.");
        PacketContainer open = manager.createPacket(OPEN);
        PacketContainer update = manager.createPacket(UPDATE);
        booleanSide = open.getBooleans().size() == 1;
        boolean supported = open.getBlockPositionModifier().size() == 1 && update.getBlockPositionModifier().size() == 1;
        if (booleanSide) {
            supported &= update.getBooleans().size() == 1 && update.getStringArrays().size() == 1;
        } else {
            supported &= open.getEnumModifier(Side.class, 1).size() == 1 && update.getEnumModifier(Side.class, 2).size() == 1
                    && update.getLists(Converters.passthrough(String.class)).size() == 1;
        }
        if (!supported) throw new IllegalStateException("The server sign editor packet format is unsupported.");
    }

    PacketContainer open(BlockPosition position) {
        PacketContainer packet = manager.createPacket(OPEN);
        packet.getBlockPositionModifier().write(0, position);
        if (booleanSide) packet.getBooleans().write(0, true);
        else packet.getEnumModifier(Side.class, 1).write(0, Side.FRONT);
        return packet;
    }

    boolean front(PacketContainer packet) {
        return booleanSide ? Boolean.TRUE.equals(packet.getBooleans().readSafely(0)) : packet.getEnumModifier(Side.class, 2).readSafely(0) == Side.FRONT;
    }

    String[] lines(PacketContainer packet) {
        if (booleanSide) return packet.getStringArrays().readSafely(0);
        List<String> lines = packet.getLists(Converters.passthrough(String.class)).readSafely(0);
        return lines != null && lines.size() == 4 ? lines.toArray(String[]::new) : null;
    }
}
