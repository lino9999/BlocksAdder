package io.github.lino9999.blocksadder.furniture;

import io.github.lino9999.blocksadder.content.ItemDefinition;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.List;
import java.util.Map;
import java.util.Locale;

record FurnitureDefinition(String id, EntityType carrier, boolean display, boolean solid, boolean small,
        boolean gravity, boolean fixedRotation, boolean opposite, boolean rotateInteraction,
        double length, double width, double height, double lengthOffset, double widthOffset, double heightOffset,
        boolean floor, boolean ceiling, boolean walls, double displayY, ItemDisplay.ItemDisplayTransform transform,
        Transformation transformation, float renderWidth, float renderHeight, Seat seat, Consumption consumption,
        Complex complex) {
    record Complex(String entity, boolean fixedPosition, int maxPerChunk, boolean staticModel, double floorOffset, double ceilingOffset, double wallOffset) {}
    record Seat(boolean enabled, double height, boolean opposite, boolean allCells, boolean front, boolean rotateBody) {}
    record Consumption(int hunger, float saturation, List<String> stages) { Consumption { stages = List.copyOf(stages); } }

    static FurnitureDefinition read(ItemDefinition item, Map<String, ConfigurationSection> entities, Map<String, ConfigurationSection> models) {
        var config = item.options().getConfigurationSection("furniture");
        if (config == null || !config.getBoolean("enabled", true)) return null;
        String context = item.id().toString();
        ConfigurationSection complexConfig = section(item, "complex_furniture");
        Complex complex = null;
        double defaultWidth = 1;
        double defaultHeight = 1;
        boolean defaultGravity = false;
        if (complexConfig != null) {
            String entity = resolve(entities, complexConfig.getString("entity"), item.id().namespace());
            ConfigurationSection entityConfig = entities.get(entity);
            defaultGravity = entityConfig.getBoolean("gravity", true);
            String model = resolve(models, entityConfig.getString("model"), entity.substring(0, entity.indexOf(':')));
            ConfigurationSection modelConfig = models.get(model);
            defaultWidth = modelConfig.getDouble("hitbox.width", .8);
            defaultHeight = modelConfig.getDouble("hitbox.height", 1.8);
            complex = new Complex(entity, complexConfig.getBoolean("fixed_position", true), (int) number(complexConfig, "max_per_chunk", 0, 0, 4096, context), complexConfig.getBoolean("static", false), number(complexConfig, "placement_offset.floor", 0, -16, 16, context), number(complexConfig, "placement_offset.ceiling", 0, -16, 16, context), number(complexConfig, "placement_offset.wall", 0, -16, 16, context));
        }
        EntityType carrier;
        try { carrier = EntityType.valueOf(config.getString("entity", "ITEM_DISPLAY").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid furniture carrier: " + context, error); }
        if (!List.of(EntityType.ITEM_DISPLAY, EntityType.ARMOR_STAND, EntityType.ITEM_FRAME, EntityType.GLOW_ITEM_FRAME).contains(carrier)) throw new IllegalArgumentException("Furniture carrier must be ITEM_DISPLAY, ARMOR_STAND, ITEM_FRAME or GLOW_ITEM_FRAME: " + context);
        boolean solid = config.getBoolean("solid", config.getBoolean("collision", complexConfig != null && complexConfig.getBoolean("solid", false)));
        double length = number(config, "hitbox.length", defaultWidth, .01, complex == null ? 4 : 8, context);
        double width = number(config, "hitbox.width", config.getDouble("width", defaultWidth), .01, complex == null ? 4 : 8, context);
        double height = number(config, "hitbox.height", config.getDouble("height", defaultHeight), .01, complex == null ? 4 : 16, context);
        double lengthOffset = number(config, "hitbox.length_offset", 0, -5, 5, context);
        double widthOffset = number(config, "hitbox.width_offset", 0, -5, 5, context);
        double heightOffset = number(config, "hitbox.height_offset", 0, -5, 5, context);
        var display = config.getConfigurationSection("display_transformation");
        ItemDisplay.ItemDisplayTransform transform = ItemDisplay.ItemDisplayTransform.FIXED;
        Vector3f translation = new Vector3f();
        float scaleValue = (float) number(config, "scale", 1, .01, 16, context);
        Vector3f scale = new Vector3f(scaleValue);
        Quaternionf left = new Quaternionf();
        Quaternionf right = new Quaternionf();
        float renderWidth = (float) width;
        float renderHeight = (float) height;
        if (display != null) {
            if (carrier != EntityType.ITEM_DISPLAY) throw new IllegalArgumentException("Furniture display transformations require ITEM_DISPLAY: " + context);
            String preset = display.getString("adjust_legacy_model", "").toLowerCase(Locale.ROOT);
            switch (preset) {
                case "armor_stand" -> { transform = ItemDisplay.ItemDisplayTransform.HEAD; translation.set(widthOffset == .5 ? -1 : 0, 1.7f, 0); scale.set(.65f); }
                case "small_armor_stand" -> { transform = ItemDisplay.ItemDisplayTransform.HEAD; translation.set(widthOffset == .5 ? -1 : 0, .92f, 0); scale.set(.45f); }
                case "item_frame" -> { transform = ItemDisplay.ItemDisplayTransform.FIXED; scale.set(.5f); right.rotationAxis((float) Math.toRadians(270), 1, 0, 0); }
                case "" -> {
                    try { transform = ItemDisplay.ItemDisplayTransform.valueOf(display.getString("transform", "FIXED").toUpperCase(Locale.ROOT)); }
                    catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid furniture item display transform: " + context, error); }
                    translation = vector(display, "translation", new Vector3f(), -16, 16, context);
                    scale = vector(display, "scale", new Vector3f(1), .01, 16, context);
                    left = rotation(display, "left_rotation", context);
                    right = rotation(display, "right_rotation", context);
                }
                default -> throw new IllegalArgumentException("Unknown furniture legacy transform preset: " + context);
            }
            renderWidth = (float) number(display, "render_size.width", width, 0, 16, context);
            renderHeight = (float) number(display, "render_size.height", height, 0, 16, context);
        }
        ConfigurationSection sit = section(item, "furniture_sit");
        Seat seat = sit == null ? new Seat(config.getBoolean("seat", false), number(config, "seat-height", .4, -4, 8, context), false, false, true, false)
                : new Seat(sit.getBoolean("enabled", true), number(sit, "sit_height", 0, -4, 8, context), sit.getBoolean("opposite_direction", true), sit.getBoolean("sit_all_solid_blocks", true), sit.getBoolean("passenger_front_view_on_sit", true), sit.getBoolean("passenger_rotate_body", false));
        ConfigurationSection consumable = section(item, "furniture_consumable");
        Consumption consumption = consumable == null ? null : new Consumption((int) number(consumable, "hunger", 0, 0, 20, context), (float) number(consumable, "saturation", 0, 0, 20, context), consumable.getStringList("furniture_list"));
        return new FurnitureDefinition(context, carrier, config.getBoolean("display", true), solid, config.getBoolean("small_hitbox", config.getBoolean("small", false)), config.getBoolean("gravity", defaultGravity) && !(complexConfig != null && (solid || complexConfig.getInt("light_level", 0) > 0)), config.getBoolean("fixed_rotation", complexConfig == null || complexConfig.getBoolean("fixed_rotation", false)), config.getBoolean("opposite_direction", complexConfig != null && complexConfig.getBoolean("opposite_direction", false)), config.getBoolean("shift_interact_rotate", true), length, width, height, lengthOffset, widthOffset, heightOffset,
                config.getBoolean("placeable_on.floor", true), config.getBoolean("placeable_on.ceiling", true), config.getBoolean("placeable_on.walls", true), number(config, "offset-y", complex == null ? .5 : 0, -16, 16, context), transform, new Transformation(translation, left, scale, right), renderWidth, renderHeight, seat, consumption,
                complex);
    }

    FurnitureDefinition withConsumption(Consumption value) {
        return new FurnitureDefinition(id, carrier, display, solid, small, gravity, fixedRotation, opposite, rotateInteraction, length, width, height, lengthOffset, widthOffset, heightOffset, floor, ceiling, walls, displayY, transform, transformation, renderWidth, renderHeight, seat, value, complex);
    }

    String complexEntity() { return complex == null ? null : complex.entity(); }
    boolean fixedPosition() { return complex == null || complex.fixedPosition(); }
    int maxPerChunk() { return complex == null ? 0 : complex.maxPerChunk(); }
    boolean staticModel() { return complex != null && complex.staticModel(); }
    LocationOffset faceOffset(org.bukkit.block.BlockFace face) {
        if (complex == null) return new LocationOffset(0, 0, 0);
        return face == org.bukkit.block.BlockFace.UP ? new LocationOffset(0, complex.floorOffset(), 0) : face == org.bukkit.block.BlockFace.DOWN ? new LocationOffset(0, complex.ceilingOffset(), 0) : new LocationOffset(face.getModX() * complex.wallOffset(), 0, face.getModZ() * complex.wallOffset());
    }
    record LocationOffset(double x, double y, double z) {}

    private static String resolve(Map<String, ConfigurationSection> definitions, String raw, String namespace) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Complex furniture requires a model-backed entity.");
        String qualified = raw.contains(":") ? raw : namespace + ":" + raw;
        if (definitions.containsKey(qualified)) return qualified;
        if (!raw.contains(":")) {
            List<String> matches = definitions.keySet().stream().filter(id -> id.endsWith(":" + raw)).limit(2).toList();
            if (matches.size() == 1) return matches.getFirst();
        }
        throw new IllegalArgumentException("Unknown or ambiguous complex furniture reference: " + raw);
    }

    static ConfigurationSection section(ItemDefinition item, String name) {
        ConfigurationSection section = item.options().getConfigurationSection(name);
        if (section == null) section = item.options().getConfigurationSection("behaviours." + name);
        return section;
    }

    boolean accepts(BlockFace face) { return face == BlockFace.UP ? floor : face == BlockFace.DOWN ? ceiling : walls; }
    float yaw(float value) { return (fixedRotation ? Math.round(value / 90f) * 90f : value) + (opposite ? 180 : 0); }
    Transformation transformCopy() { return new Transformation(new Vector3f(transformation.getTranslation()), new Quaternionf(transformation.getLeftRotation()), new Vector3f(transformation.getScale()), new Quaternionf(transformation.getRightRotation())); }

    private static Quaternionf rotation(ConfigurationSection config, String path, String context) {
        if (config.isConfigurationSection(path + ".quaternion")) {
            Quaternionf value = new Quaternionf((float) number(config, path + ".quaternion.x", 0, -1, 1, context), (float) number(config, path + ".quaternion.y", 0, -1, 1, context), (float) number(config, path + ".quaternion.z", 0, -1, 1, context), (float) number(config, path + ".quaternion.w", 1, -1, 1, context));
            if (value.lengthSquared() < .000001f) throw new IllegalArgumentException("Furniture rotation quaternion cannot be zero: " + context);
            return value.normalize();
        }
        if (!config.isConfigurationSection(path + ".axis_angle")) return new Quaternionf();
        Vector3f axis = vector(config, path + ".axis_angle.axis", new Vector3f(0, 1, 0), -1, 1, context);
        if (axis.lengthSquared() < .000001f) throw new IllegalArgumentException("Furniture rotation axis cannot be zero: " + context);
        axis.normalize();
        float angle = (float) number(config, path + ".axis_angle.angle", 0, -36000, 36000, context);
        return new Quaternionf(new AxisAngle4f((float) Math.toRadians(angle), axis));
    }

    private static Vector3f vector(ConfigurationSection config, String path, Vector3f fallback, double min, double max, String context) {
        if (!config.isConfigurationSection(path)) return fallback;
        return new Vector3f((float) number(config, path + ".x", fallback.x, min, max, context), (float) number(config, path + ".y", fallback.y, min, max, context), (float) number(config, path + ".z", fallback.z, min, max, context));
    }

    static double number(ConfigurationSection config, String path, double fallback, double min, double max, String context) {
        double value = config.getDouble(path, fallback);
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException("Furniture value outside supported range: " + context + "/" + path);
        return value;
    }
}
