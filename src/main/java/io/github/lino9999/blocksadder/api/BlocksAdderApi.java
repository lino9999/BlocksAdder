package io.github.lino9999.blocksadder.api;

import io.github.lino9999.blocksadder.content.ItemDefinition;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.Collection;
import java.util.Optional;

public interface BlocksAdderApi {
    Collection<ItemDefinition> definitions();
    Optional<ItemDefinition> find(String id);
    ItemStack createItem(String id, int amount);
    Optional<String> identify(ItemStack item);
    Optional<String> identify(Block block);
    boolean place(Location location, String id, Player player);
    Entity spawnEntity(Location location, String id);
    double stat(Player player, String id);
    double stat(Player player, String id, double value);
}
