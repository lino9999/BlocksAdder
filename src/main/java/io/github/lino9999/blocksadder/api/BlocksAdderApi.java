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
    boolean removeBlock(Location location, Player player, boolean drops);
    boolean placeLiquid(Location location, String id, Player player);
    boolean placeFire(Location location, String id, Player player);
    Optional<String> identifyEnvironment(Block block);
    java.util.UUID attachModel(Entity entity, String model);
    boolean playAnimation(java.util.UUID modelInstance, String animation, boolean loop);
    void detachModel(java.util.UUID modelInstance);
    boolean disguise(Player player, String model);
    void undisguise(Player player);
    Entity spawnVehicle(Location location, String id);
    boolean mountVehicle(Player player, java.util.UUID vehicle);
    double vehicleFuel(java.util.UUID vehicle, double value);
    boolean growCrop(Location location, int stages, Player player);
    double modelVariable(java.util.UUID modelInstance, String name, double value);
    java.util.Map<String, Double> modelVariables(java.util.UUID modelInstance);
    boolean modelCamera(java.util.UUID modelInstance, Player viewer, double distance);
    boolean stopModelCamera(Player viewer);
    boolean playEmote(Player player, String id);
    boolean stopEmote(Player player, boolean immediate);
    String emoteState(Player player);
}
