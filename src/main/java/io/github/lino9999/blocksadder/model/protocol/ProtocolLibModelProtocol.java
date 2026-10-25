package io.github.lino9999.blocksadder.model.protocol;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

final class ProtocolLibModelProtocol implements ModelProtocol {
    private final ProtocolManager manager;
    private final Map<UUID, Rotation> cameras = new ConcurrentHashMap<>();
    private final Map<UUID, Mask> masks = new ConcurrentHashMap<>();
    private final Map<Integer, Mask> maskedEntities = new ConcurrentHashMap<>();

    ProtocolLibModelProtocol(JavaPlugin plugin) {
        manager = ProtocolLibrary.getProtocolManager();
        if (!PacketType.Play.Server.CAMERA.isSupported() || !PacketType.Play.Server.MOUNT.isSupported()
                || !PacketType.Play.Server.GAME_STATE_CHANGE.isSupported()) throw new IllegalStateException("This ProtocolLib build does not support the required camera packets.");
        PacketType[] movement = java.util.stream.Stream.of(PacketType.Play.Client.POSITION, PacketType.Play.Client.POSITION_LOOK,
                PacketType.Play.Client.LOOK, PacketType.Play.Client.FLYING, PacketType.Play.Client.VEHICLE_MOVE,
                PacketType.Play.Server.ENTITY_EQUIPMENT, PacketType.Play.Server.WINDOW_ITEMS, PacketType.Play.Server.SET_SLOT,
                PacketType.Play.Server.SET_PLAYER_INVENTORY).filter(PacketType::isSupported).toArray(PacketType[]::new);
        manager.addPacketListener(new PacketAdapter(PacketAdapter.params().plugin(plugin).listenerPriority(ListenerPriority.HIGHEST)
                .optionSync().types(movement)) {
            @Override public void onPacketReceiving(PacketEvent event) {
                if (event.isCancelled()) return;
                UUID id = event.getPlayer().getUniqueId();
                if (!cameras.containsKey(id)) return;
                event.setCancelled(true);
                if (event.getPacketType().equals(PacketType.Play.Client.LOOK) || event.getPacketType().equals(PacketType.Play.Client.POSITION_LOOK)) {
                    Float yaw = event.getPacket().getFloat().readSafely(0);
                    Float pitch = event.getPacket().getFloat().readSafely(1);
                    if (yaw != null && pitch != null && Float.isFinite(yaw) && Float.isFinite(pitch)) cameras.put(id, new Rotation((yaw % 360 + 540) % 360 - 180, Math.clamp(pitch, -89.9f, 89.9f)));
                }
            }

            @Override public void onPacketSending(PacketEvent event) {
                if (!event.isCancelled()) mask(event);
            }
        });
    }

    @Override public boolean available() { return true; }
    @Override public String reason() { return "Model camera packets are available."; }

    @Override public void begin(Player player, Entity camera) {
        cameras.put(player.getUniqueId(), new Rotation(player.getLocation().getYaw(), player.getLocation().getPitch()));
        sendCamera(player, camera.getEntityId());
        passengers(player, camera.getEntityId(), new int[]{player.getEntityId()});
        gameMode(player, 3);
    }

    @Override public void end(Player player, Entity camera) {
        cameras.remove(player.getUniqueId());
        sendCamera(player, player.getEntityId());
        passengers(player, camera.getEntityId(), new int[0]);
        gameMode(player, player.getGameMode().getValue());
    }

    @Override public Rotation rotation(Player player) {
        return cameras.getOrDefault(player.getUniqueId(), new Rotation(player.getLocation().getYaw(), player.getLocation().getPitch()));
    }

    @Override public void equipment(Player player, Set<EquipmentSlot> hidden) {
        Mask previous = masks.remove(player.getUniqueId());
        if (previous == null && hidden.isEmpty()) return;
        if (previous != null) maskedEntities.remove(previous.entity);
        if (!hidden.isEmpty()) {
            Mask mask = new Mask(player.getEntityId(), Set.copyOf(hidden));
            masks.put(player.getUniqueId(), mask);
            maskedEntities.put(mask.entity, mask);
        }
        if (player.isOnline()) player.updateInventory();
    }

    private void mask(PacketEvent event) {
        PacketContainer packet = event.getPacket();
        if (event.getPacketType().equals(PacketType.Play.Server.ENTITY_EQUIPMENT)) {
            Integer id = packet.getIntegers().readSafely(0);
            Mask mask = id == null ? null : maskedEntities.get(id);
            if (mask == null) return;
            var source = packet.getSlotStackPairLists().readSafely(0);
            if (source == null) return;
            List<Pair<EnumWrappers.ItemSlot, ItemStack>> values = new ArrayList<>();
            for (var pair : source) {
                EquipmentSlot slot = switch (pair.getFirst()) {
                    case MAINHAND -> EquipmentSlot.HAND;
                    case OFFHAND -> EquipmentSlot.OFF_HAND;
                    case HEAD -> EquipmentSlot.HEAD;
                    default -> null;
                };
                values.add(new Pair<>(pair.getFirst(), slot != null && mask.slots.contains(slot) ? new ItemStack(Material.AIR) : pair.getSecond()));
            }
            PacketContainer copy = packet.shallowClone();
            copy.getSlotStackPairLists().write(0, values);
            event.setPacket(copy);
            return;
        }
        Mask mask = masks.get(event.getPlayer().getUniqueId());
        if (mask == null) return;
        if (event.getPacketType().equals(PacketType.Play.Server.WINDOW_ITEMS)) {
            Integer window = packet.getIntegers().readSafely(0);
            if (window == null) return;
            List<ItemStack> source = packet.getItemListModifier().readSafely(0);
            if (source == null) return;
            List<ItemStack> values = new ArrayList<>(source);
            for (int index = 0; index < values.size(); index++) if (windowSlot(mask, event.getPlayer(), window, index)) values.set(index, new ItemStack(Material.AIR));
            PacketContainer copy = packet.shallowClone();
            copy.getItemListModifier().write(0, values);
            event.setPacket(copy);
        } else if (event.getPacketType().equals(PacketType.Play.Server.SET_SLOT)) {
            Integer window = packet.getIntegers().readSafely(0);
            Integer slot = packet.getIntegers().readSafely(2);
            if (window != null && slot != null && windowSlot(mask, event.getPlayer(), window, slot)) empty(event);
        } else if (event.getPacketType().equals(PacketType.Play.Server.SET_PLAYER_INVENTORY)) {
            Integer slot = packet.getIntegers().readSafely(0);
            if (slot != null && inventorySlot(mask, event.getPlayer(), slot)) empty(event);
        }
    }

    private boolean windowSlot(Mask mask, Player player, int window, int slot) {
        if (window != 0) {
            var view = player.getOpenInventory();
            return slot >= 0 && slot < view.countSlots() && player.getInventory().equals(view.getInventory(slot)) && inventorySlot(mask, player, view.convertSlot(slot));
        }
        return mask.slots.contains(EquipmentSlot.HEAD) && slot == 5 || mask.slots.contains(EquipmentSlot.OFF_HAND) && slot == 45
                || mask.slots.contains(EquipmentSlot.HAND) && slot == 36 + player.getInventory().getHeldItemSlot();
    }

    private boolean inventorySlot(Mask mask, Player player, int slot) {
        return mask.slots.contains(EquipmentSlot.HEAD) && slot == 39 || mask.slots.contains(EquipmentSlot.OFF_HAND) && slot == 40
                || mask.slots.contains(EquipmentSlot.HAND) && slot == player.getInventory().getHeldItemSlot();
    }

    private void empty(PacketEvent event) {
        PacketContainer copy = event.getPacket().shallowClone();
        copy.getItemModifier().write(0, new ItemStack(Material.AIR));
        event.setPacket(copy);
    }

    private void sendCamera(Player player, int id) {
        var packet = manager.createPacket(PacketType.Play.Server.CAMERA);
        packet.getIntegers().write(0, id);
        manager.sendServerPacket(player, packet);
    }

    private void passengers(Player player, int vehicle, int[] passengers) {
        var packet = manager.createPacket(PacketType.Play.Server.MOUNT);
        packet.getIntegers().write(0, vehicle);
        packet.getIntegerArrays().write(0, passengers);
        manager.sendServerPacket(player, packet);
    }

    private void gameMode(Player player, int mode) {
        var packet = manager.createPacket(PacketType.Play.Server.GAME_STATE_CHANGE);
        packet.getGameStateIDs().write(0, 3);
        packet.getFloat().write(0, (float) mode);
        manager.sendServerPacket(player, packet);
    }

    private record Mask(int entity, Set<EquipmentSlot> slots) {}
}
