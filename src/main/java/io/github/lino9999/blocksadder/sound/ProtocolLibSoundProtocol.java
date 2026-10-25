package io.github.lino9999.blocksadder.sound;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.MinecraftKey;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.function.BiConsumer;

final class ProtocolLibSoundProtocol implements SoundProtocol {
    private final JavaPlugin plugin;
    private final ProtocolManager manager;
    private final PacketAdapter listener;
    private volatile boolean active;

    ProtocolLibSoundProtocol(JavaPlugin plugin, BiConsumer<Player, Dig> dig) {
        this.plugin = plugin;
        manager = ProtocolLibrary.getProtocolManager();
        for (PacketType type : new PacketType[]{PacketType.Play.Server.NAMED_SOUND_EFFECT, PacketType.Play.Server.ENTITY_SOUND, PacketType.Play.Server.STOP_SOUND, PacketType.Play.Client.BLOCK_DIG})
            if (!type.isSupported()) throw new IllegalStateException("The installed ProtocolLib build does not support required sound packets.");
        PacketContainer sound = manager.createPacket(PacketType.Play.Server.NAMED_SOUND_EFFECT);
        if (sound.getSoundEffects().size() < 1 || sound.getSoundCategories().size() < 1 || sound.getIntegers().size() < 3 || sound.getFloat().size() < 2) throw new IllegalStateException("Unsupported named sound packet layout.");
        sound.getSoundEffects().write(0, Sound.BLOCK_STONE_STEP);
        Sound decoded = sound.getSoundEffects().readSafely(0);
        if (decoded == null || !decoded.getKey().equals(Sound.BLOCK_STONE_STEP.getKey())) throw new IllegalStateException("Unsupported sound registry conversion.");
        PacketContainer entitySound = manager.createPacket(PacketType.Play.Server.ENTITY_SOUND);
        if (entitySound.getSoundEffects().size() < 1 || entitySound.getSoundCategories().size() < 1 || entitySound.getIntegers().size() < 1 || entitySound.getFloat().size() < 2) throw new IllegalStateException("Unsupported entity sound packet layout.");
        PacketContainer stop = manager.createPacket(PacketType.Play.Server.STOP_SOUND);
        if (stop.getMinecraftKeys().size() < 1 || stop.getSoundCategories().size() < 1) throw new IllegalStateException("Unsupported stop sound packet layout.");
        PacketContainer digging = manager.createPacket(PacketType.Play.Client.BLOCK_DIG);
        if (digging.getBlockPositionModifier().size() < 1 || digging.getPlayerDigTypes().size() < 1) throw new IllegalStateException("Unsupported sound digging packet layout.");
        listener = new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.NAMED_SOUND_EFFECT, PacketType.Play.Server.ENTITY_SOUND, PacketType.Play.Server.STOP_SOUND, PacketType.Play.Client.BLOCK_DIG) {
            @Override public void onPacketSending(PacketEvent event) {
                if (event.isCancelled() || event.isPlayerTemporary() || !active) return;
                if (event.getPacketType().equals(PacketType.Play.Server.STOP_SOUND)) stop(event);
                else sound(event);
            }
            @Override public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled() || event.isPlayerTemporary() || !active) return;
                BlockPosition position = event.getPacket().getBlockPositionModifier().readSafely(0);
                EnumWrappers.PlayerDigType type = event.getPacket().getPlayerDigTypes().readSafely(0);
                if (position == null || type == null || type != EnumWrappers.PlayerDigType.START_DESTROY_BLOCK && type != EnumWrappers.PlayerDigType.ABORT_DESTROY_BLOCK && type != EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) return;
                Dig state = new Dig(position.getX(), position.getY(), position.getZ(), type == EnumWrappers.PlayerDigType.START_DESTROY_BLOCK);
                dispatch(() -> dig.accept(event.getPlayer(), state));
            }
        };
        active = true;
        manager.addPacketListener(listener);
    }

    private void sound(PacketEvent event) {
        PacketContainer packet = event.getPacket();
        String key;
        try {
            Sound original = packet.getSoundEffects().readSafely(0);
            if (original == null) return;
            key = original.getKey().toString();
        }
        catch (RuntimeException exception) { return; }
        if (!VanillaSoundAliases.muted(key)) return;
        EnumWrappers.SoundCategory wrapped = packet.getSoundCategories().readSafely(0);
        if (wrapped == null) return;
        SoundCategory category = SoundCategory.valueOf(wrapped.name());
        Float volume = packet.getFloat().readSafely(0);
        Float pitch = packet.getFloat().readSafely(1);
        if (volume == null || pitch == null) return;
        Long seed = packet.getLongs().readSafely(0);
        String alias = VanillaSoundAliases.alias(key);
        Player receiver = event.getPlayer();
        if (event.getPacketType().equals(PacketType.Play.Server.ENTITY_SOUND)) {
            Integer id = packet.getIntegers().readSafely(0);
            if (id == null) return;
            event.setCancelled(true);
            dispatch(() -> {
                if (!active || !receiver.isOnline()) return;
                Entity entity = manager.getEntityFromID(receiver.getWorld(), id);
                if (entity != null) receiver.playSound(entity, alias, category, volume, pitch, seed == null ? 0 : seed.longValue());
            });
        } else {
            Integer x = packet.getIntegers().readSafely(0);
            Integer y = packet.getIntegers().readSafely(1);
            Integer z = packet.getIntegers().readSafely(2);
            if (x == null || y == null || z == null) return;
            event.setCancelled(true);
            dispatch(() -> {
                if (active && receiver.isOnline()) receiver.playSound(new Location(receiver.getWorld(), x / 8d, y / 8d, z / 8d), alias, category, volume, pitch, seed == null ? 0 : seed.longValue());
            });
        }
    }

    private void stop(PacketEvent event) {
        MinecraftKey key;
        try { key = event.getPacket().getMinecraftKeys().readSafely(0); }
        catch (RuntimeException exception) { return; }
        if (key == null || !VanillaSoundAliases.muted(key.getFullKey())) return;
        PacketContainer replacement = event.getPacket().shallowClone();
        String alias = VanillaSoundAliases.alias(key.getFullKey());
        int separator = alias.indexOf(':');
        replacement.getMinecraftKeys().write(0, new MinecraftKey(alias.substring(0, separator), alias.substring(separator + 1)));
        Player receiver = event.getPlayer();
        event.setCancelled(true);
        dispatch(() -> {
            if (active && receiver.isOnline()) manager.sendServerPacket(receiver, replacement, false);
        });
    }

    private void dispatch(Runnable work) {
        if (Bukkit.isPrimaryThread()) work.run();
        else Bukkit.getScheduler().runTask(plugin, work);
    }

    @Override public boolean active() { return active; }
    @Override public void close() { active = false; manager.removePacketListener(listener); }
}
