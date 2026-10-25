package io.github.lino9999.blocksadder.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.model.ModelDefinition.Animation;
import io.github.lino9999.blocksadder.model.ModelDefinition.Bone;
import io.github.lino9999.blocksadder.model.ModelDefinition.Keyframe;
import io.github.lino9999.blocksadder.model.ModelDefinition.Track;
import io.github.lino9999.blocksadder.model.ModelDefinition.Vector;
import io.github.lino9999.blocksadder.util.Json;
import org.bukkit.configuration.ConfigurationSection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ModelLoader {
    private final Path root;

    public ModelLoader(Path root) { this.root = root; }

    public Map<String, ModelDefinition> load(ContentRegistry registry) {
        Map<String, ModelDefinition> result = new TreeMap<>();
        new TreeMap<>(registry.sections("models")).forEach((id, section) -> result.put(id, load(id, section, registry)));
        return Map.copyOf(result);
    }

    private ModelDefinition load(String id, ConfigurationSection config, ContentRegistry registry) {
        float scale = bounded(config.getDouble("scale", 1), 0.01, 16, "Model scale");
        float range = bounded(config.getDouble("view-range", 48), 4, 256, "Model view range");
        float width = bounded(config.getDouble("hitbox.width", 0.8), 0.1, 8, "Model hitbox width");
        float height = bounded(config.getDouble("hitbox.height", 1.8), 0.1, 16, "Model hitbox height");
        Map<String, Bone> bones = new LinkedHashMap<>();
        Map<String, Animation> animations = new TreeMap<>();
        Map<String, byte[]> assets = new TreeMap<>();
        String file = config.getString("file", "");
        if (!file.isBlank() && (file.endsWith(".iaentitymodel") || file.endsWith(".player_animations"))) readLegacy(id, config, file, bones, animations, assets);
        else if (!file.isBlank()) readBlockbench(id, config, file, bones, animations, assets);
        else readBones(config.getConfigurationSection("bones"), registry, bones);
        if (bones.isEmpty() || bones.size() > 512) throw new IllegalArgumentException("Model " + id + " must have between 1 and 512 bones.");
        ConfigurationSection configuredAnimations = config.getConfigurationSection("animations");
        if (configuredAnimations != null) {
            for (String name : configuredAnimations.getKeys(false)) {
                ConfigurationSection section = configuredAnimations.getConfigurationSection(name);
                if (section == null) throw new IllegalArgumentException("Animation must be a section: " + name);
                Map<String, Track> tracks = new TreeMap<>();
                ConfigurationSection animatedBones = section.getConfigurationSection("bones");
                if (animatedBones != null) for (String bone : animatedBones.getKeys(false)) {
                    ConfigurationSection values = animatedBones.getConfigurationSection(bone);
                    if (values == null || !bones.containsKey(bone)) throw new IllegalArgumentException("Animation references unknown bone: " + bone);
                    tracks.put(bone, new Track(frames(values, "position"), frames(values, "rotation"), frames(values, "scale")));
                }
                float duration = bounded(section.getDouble("length", 1), 0.05, 3600, "Animation length");
                String loop = loopMode(section.getString("loop", "once"));
                validateTimes(tracks, duration);
                animations.put(name, new Animation(name, duration, loop, 0, duration, tracks, ModelEffectLoader.yaml(section, bones.keySet(), duration)));
            }
        }
        if (animations.size() > 256) throw new IllegalArgumentException("A model may define at most 256 animations.");
        List<Bone> ordered = new ArrayList<>();
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        bones.values().forEach(bone -> order(bone, bones, visiting, visited, ordered));
        return new ModelDefinition(id, scale, range, config.getBoolean("hide-base", true), config.getBoolean("self-visible", false), width, height, ordered, animations, assets);
    }

    private void readBones(ConfigurationSection configured, ContentRegistry registry, Map<String, Bone> bones) {
        if (configured == null) throw new IllegalArgumentException("A model requires bones or a Blockbench file.");
        for (String name : configured.getKeys(false)) {
            ConfigurationSection bone = configured.getConfigurationSection(name);
            if (bone == null) throw new IllegalArgumentException("Bone must be a section: " + name);
            String item = bone.getString("item");
            if (item != null) item = registry.find(item).orElseThrow(() -> new IllegalArgumentException("Unknown bone item: " + bone.getString("item"))).id().toString();
            String model = bone.getString("model");
            if (model != null) ContentId.parse(model);
            Vector pivot = vector(bone.getList("origin", List.of(0, 0, 0)), Vector.ZERO);
            Vector rotation = vector(bone.getList("rotation", List.of(0, 0, 0)), Vector.ZERO);
            Vector scale = vector(bone.getList("scale", List.of(1, 1, 1)), Vector.ONE);
            boolean mount = bone.getBoolean("mount", name.startsWith("mount_"));
            ModelDefinition.Role role = role(bone.getString("role", mount ? "mount" : "normal"));
            if (Set.of(ModelDefinition.Role.HAT_PIVOT, ModelDefinition.Role.LEFT_HAND_PIVOT, ModelDefinition.Role.RIGHT_HAND_PIVOT).contains(role) && (item != null || model != null)) throw new IllegalArgumentException("Equipment pivot bones mirror the target equipment and cannot define a static item or model.");
            addBone(bones, new Bone(name, bone.getString("parent"), pivot, rotation, scale, item, model, 1, mount, false, role,
                    bounded(bone.getDouble("max-head-rotation-x", 70), 0, 180, "Head pitch limit"), bounded(bone.getDouble("max-head-rotation-y", 45), 0, 180, "Head yaw limit")));
        }
    }

    private List<Keyframe> frames(ConfigurationSection section, String channel) {
        List<Keyframe> result = new ArrayList<>();
        for (Map<?, ?> entry : section.getMapList(channel)) {
            float time = number(entry.get("time"), 0);
            Vector fallback = channel.equals("scale") ? Vector.ONE : Vector.ZERO;
            ModelDefinition.Value value = animatedVector(entry.get("value"), fallback);
            Object post = entry.containsKey("post-value") ? entry.get("post-value") : entry.get("post_value");
            ModelDefinition.Value after = post == null ? value : animatedVector(post, fallback);
            String interpolation = interpolation(String.valueOf(entry.containsKey("interpolation") ? entry.get("interpolation") : "linear"));
            result.add(new Keyframe(time, value.constant(), interpolation, value, after,
                    new ModelDefinition.Bezier(vector(field(entry, "bezier-left-time"), new Vector(-0.1f, -0.1f, -0.1f)),
                            vector(field(entry, "bezier-left-value"), Vector.ZERO), vector(field(entry, "bezier-right-time"), new Vector(0.1f, 0.1f, 0.1f)),
                            vector(field(entry, "bezier-right-value"), Vector.ZERO))));
        }
        return sorted(result);
    }

    private void readLegacy(String id, ConfigurationSection config, String filename, Map<String, Bone> bones, Map<String, Animation> animations, Map<String, byte[]> assets) {
        JsonObject model;
        try {
            Path source = root.resolve(filename).normalize();
            if (!source.startsWith(root.normalize()) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !source.toRealPath().startsWith(root.toRealPath()) || Files.size(source) > 16 * 1024 * 1024) throw new IOException("Legacy model path must stay inside the plugin data directory and be no larger than 16 MiB.");
            model = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Cannot read legacy entity model " + id + ": " + exception.getMessage(), exception);
        }
        ContentId content = ContentId.parse(id);
        String stem = Path.of(filename).getFileName().toString().replaceFirst("\\.(?:iaentitymodel|player_animations)$", "");
        String geometryRoot = config.getString("legacy-model-root", content.namespace() + ":entity/" + stem);
        ContentId.parse(geometryRoot);
        Map<String, JsonObject> sourceBones = new LinkedHashMap<>();
        for (JsonElement element : array(model, "bones")) {
            JsonObject bone = element.getAsJsonObject();
            String name = text(bone, "name", "");
            if (sourceBones.put(name, bone) != null) throw new IllegalArgumentException("Duplicate legacy model bone: " + name);
        }
        for (var entry : sourceBones.entrySet()) {
            String name = entry.getKey();
            JsonObject bone = entry.getValue();
            ModelDefinition.Role type = role(text(bone, "boneType", "NORMAL"));
            JsonArray parents = array(bone, "parents");
            String parent = null;
            for (JsonElement ancestor : parents) {
                String candidate = ancestor.getAsString();
                if (sourceBones.containsKey(candidate)) parent = candidate;
            }
            boolean drawable = type == ModelDefinition.Role.NORMAL || type == ModelDefinition.Role.HEAD;
            String generated = null;
            if (drawable) {
                String geometry = config.getString("bone-models." + name, geometryRoot + "/" + name);
                ContentId.parse(geometry);
                generated = content.namespace() + ":blocksadder_models/" + content.value() + "/bone_" + bones.size();
                String value = generated.substring(generated.indexOf(':') + 1);
                assets.put("assets/" + content.namespace() + "/items/" + value + ".json", Json.write(Map.of("model", Map.of("type", "minecraft:model", "model", geometry))).getBytes(StandardCharsets.UTF_8));
            }
            addBone(bones, new Bone(name, parent, vector(bone.get("pos"), Vector.ZERO).multiply(16), vector(bone.get("rot"), Vector.ZERO), Vector.ONE, null, generated, 1, type == ModelDefinition.Role.MOUNT_PIVOT, true, type,
                    bounded(decimal(bone, "maxHeadRotX", 70), 0, 180, "Legacy head pitch limit"), bounded(decimal(bone, "maxHeadRotY", 45), 0, 180, "Legacy head yaw limit")));
        }
        for (JsonElement element : array(model, "animations")) {
            JsonObject animation = element.getAsJsonObject();
            String name = text(animation, "name", "animation_" + animations.size());
            float duration = bounded(decimal(animation, "length", 1), 0.05, 3600, "Legacy animation length");
            String loop = loopMode(text(animation, "loopMode", "once"));
            if (name.equalsIgnoreCase("death")) loop = "once";
            float start = (float) decimal(animation, "loopStartTime", 0);
            float end = (float) decimal(animation, "loopEndTime", duration);
            if (start < 0) start = 0;
            if (end <= 0) end = duration;
            if (start >= end || end > duration) throw new IllegalArgumentException("Invalid legacy animation loop interval.");
            Map<String, Track> tracks = new TreeMap<>();
            for (var entry : object(animation, "bones").entrySet()) {
                Bone bone = bones.get(entry.getKey());
                if (bone == null) throw new IllegalArgumentException("Legacy animation references unknown bone: " + entry.getKey());
                List<Keyframe> positions = new ArrayList<>();
                List<Keyframe> rotations = new ArrayList<>();
                List<Keyframe> scales = new ArrayList<>();
                JsonArray frames = array(entry.getValue().getAsJsonObject(), "keyframes");
                for (int index = 0; index < frames.size(); index++) {
                    JsonObject frame = frames.get(index).getAsJsonObject();
                    float time = index / 20f;
                    Vector scale = Vector.ONE;
                    if (frame.has("scale_str")) {
                        String configured = frame.get("scale_str").getAsString();
                        String[] parts = configured.replaceFirst("^-", "").split("-");
                        if (parts.length != 3) throw new IllegalArgumentException("Legacy scale_str needs three values.");
                        scale = new Vector(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
                    }
                    positions.add(new Keyframe(time, vector(frame.get("pos"), bone.pivot().multiply(1f / 16)).multiply(16), "step"));
                    rotations.add(new Keyframe(time, vector(frame.get("rot"), bone.rotation()), "step"));
                    scales.add(new Keyframe(time, scale, "step"));
                }
                tracks.put(entry.getKey(), new Track(sorted(positions), sorted(rotations), sorted(scales)));
            }
            validateTimes(tracks, duration);
            if (animations.put(name, new Animation(name, duration, loop, start, end, tracks, ModelEffectLoader.legacy(animation, bones.keySet(), duration))) != null) throw new IllegalArgumentException("Duplicate legacy animation: " + name);
        }
    }

    private void readBlockbench(String id, ConfigurationSection config, String filename, Map<String, Bone> bones, Map<String, Animation> animations, Map<String, byte[]> assets) {
        JsonObject model;
        try {
            Path source = root.resolve(filename).normalize();
            if (!source.startsWith(root.normalize()) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || !source.toRealPath().startsWith(root.toRealPath())) throw new IOException("Model path must stay inside the plugin data directory.");
            if (!filename.endsWith(".bbmodel") || Files.size(source) > 16 * 1024 * 1024) throw new IOException("Use a .bbmodel file no larger than 16 MiB.");
            model = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Cannot read Blockbench model " + id + ": " + exception.getMessage(), exception);
        }
        JsonArray elements = array(model, "elements");
        if (elements.size() > 512) throw new IllegalArgumentException("Blockbench model has more than 512 elements: " + id);
        Map<String, JsonObject> cubes = new LinkedHashMap<>();
        for (JsonElement element : elements) {
            JsonObject cube = element.getAsJsonObject();
            if (!Set.of("cube", "locator").contains(text(cube, "type", "cube"))) throw new IllegalArgumentException("Only Blockbench cube and locator elements are supported: " + id);
            if (!cube.has("uuid")) throw new IllegalArgumentException("Blockbench element is missing a UUID.");
            if (cubes.put(cube.get("uuid").getAsString(), cube) != null) throw new IllegalArgumentException("Duplicate Blockbench element UUID.");
        }
        Map<String, String> textures = cubes.values().stream().anyMatch(element -> text(element, "type", "cube").equals("cube")) ? textures(id, model, config, assets) : Map.of();
        Map<String, String> names = new HashMap<>();
        Set<String> included = new HashSet<>();
        for (JsonElement element : array(model, "outliner")) outliner(id, model, element, null, cubes, textures, bones, assets, names, included);
        for (var cube : cubes.entrySet()) if (!included.contains(cube.getKey())) cube(id, model, cube.getKey(), cube.getValue(), null, textures, bones, assets, names);
        for (JsonElement element : array(model, "animations")) {
            JsonObject animation = element.getAsJsonObject();
            String name = text(animation, "name", "animation_" + animations.size());
            float length = bounded(decimal(animation, "length", 1), 0.05, 3600, "Animation length");
            Map<String, Track> tracks = new TreeMap<>();
            JsonObject animators = object(animation, "animators");
            for (var entry : animators.entrySet()) {
                JsonObject animator = entry.getValue().getAsJsonObject();
                String boneName = names.get(entry.getKey());
                if (boneName == null) {
                    if (text(animator, "type", "bone").equals("effect") || entry.getKey().equals("effects")) continue;
                    boneName = text(animator, "name", "");
                }
                if (!bones.containsKey(boneName)) throw new IllegalArgumentException("Blockbench animation references unknown bone: " + boneName);
                List<Keyframe> position = new ArrayList<>();
                List<Keyframe> rotation = new ArrayList<>();
                List<Keyframe> scale = new ArrayList<>();
                for (JsonElement keyframeElement : array(animator, "keyframes")) {
                    JsonObject keyframe = keyframeElement.getAsJsonObject();
                    String channel = text(keyframe, "channel", "rotation");
                    JsonArray points = array(keyframe, "data_points");
                    if (points.isEmpty()) continue;
                    float defaultValue = channel.equals("scale") ? 1 : 0;
                    Vector fallback = new Vector(defaultValue, defaultValue, defaultValue);
                    ModelDefinition.Value value = animatedVector(points.get(0), fallback);
                    ModelDefinition.Value after = points.size() < 2 ? value : animatedVector(points.get(1), fallback);
                    if (points.size() > 2) throw new IllegalArgumentException("A Blockbench transform keyframe supports at most two data points.");
                    Keyframe frame = new Keyframe((float) decimal(keyframe, "time", 0), value.constant(), interpolation(text(keyframe, "interpolation", "linear")), value, after,
                            new ModelDefinition.Bezier(vector(keyframe.get("bezier_left_time"), new Vector(-0.1f, -0.1f, -0.1f)),
                                    vector(keyframe.get("bezier_left_value"), Vector.ZERO), vector(keyframe.get("bezier_right_time"), new Vector(0.1f, 0.1f, 0.1f)),
                                    vector(keyframe.get("bezier_right_value"), Vector.ZERO)));
                    switch (channel) {
                        case "position" -> position.add(frame);
                        case "rotation" -> rotation.add(frame);
                        case "scale" -> scale.add(frame);
                        default -> throw new IllegalArgumentException("Unsupported Blockbench animation channel: " + channel);
                    }
                }
                tracks.put(boneName, new Track(sorted(position), sorted(rotation), sorted(scale)));
            }
            validateTimes(tracks, length);
            if (animations.put(name, new Animation(name, length, loopMode(text(animation, "loop", "once")), 0, length, tracks, ModelEffectLoader.blockbench(animation, bones.keySet(), names, length))) != null) throw new IllegalArgumentException("Duplicate Blockbench animation: " + name);
        }
    }

    private Map<String, String> textures(String id, JsonObject model, ConfigurationSection config, Map<String, byte[]> assets) {
        Map<String, String> textures = new LinkedHashMap<>();
        ContentId content = ContentId.parse(id);
        JsonArray configured = array(model, "textures");
        for (int index = 0; index < configured.size(); index++) {
            JsonObject texture = configured.get(index).getAsJsonObject();
            String key = content.namespace() + ":item/blocksadder_models/" + content.value() + "/texture_" + index;
            String source = text(texture, "source", "");
            if (source.startsWith("data:image/png;base64,")) {
                String base64 = source.substring("data:image/png;base64,".length());
                if (base64.length() > 12 * 1024 * 1024) throw new IllegalArgumentException("Embedded model texture is too large.");
                byte[] bytes = Base64.getDecoder().decode(base64);
                if (bytes.length < 24 || bytes[0] != (byte) 137 || bytes[1] != 80 || bytes[2] != 78 || bytes[3] != 71) throw new IllegalArgumentException("Embedded model texture is not a PNG.");
                int width = java.nio.ByteBuffer.wrap(bytes, 16, 4).getInt();
                int height = java.nio.ByteBuffer.wrap(bytes, 20, 4).getInt();
                if (width < 1 || height < 1 || width > 4096 || height > 4096) throw new IllegalArgumentException("Embedded model texture dimensions exceed 4096 pixels.");
                assets.put("assets/" + content.namespace() + "/textures/item/blocksadder_models/" + content.value() + "/texture_" + index + ".png", bytes);
            } else {
                key = config.getString("textures." + index);
                if (key == null) throw new IllegalArgumentException("Blockbench texture " + index + " must be embedded or have a namespaced textures override.");
                ContentId.parse(key);
            }
            textures.put(String.valueOf(index), key);
        }
        if (textures.isEmpty()) throw new IllegalArgumentException("A Blockbench model requires at least one texture.");
        return textures;
    }

    private void outliner(String id, JsonObject model, JsonElement element, String parent, Map<String, JsonObject> cubes, Map<String, String> textures,
                          Map<String, Bone> bones, Map<String, byte[]> assets, Map<String, String> names, Set<String> included) {
        if (element.isJsonPrimitive()) {
            String uuid = element.getAsString();
            JsonObject cube = cubes.get(uuid);
            if (cube == null || !included.add(uuid)) throw new IllegalArgumentException("Unknown or repeated Blockbench element: " + uuid);
            cube(id, model, uuid, cube, parent, textures, bones, assets, names);
            return;
        }
        JsonObject group = element.getAsJsonObject();
        String uuid = text(group, "uuid", "group_" + bones.size());
        String name = unique(text(group, "name", "bone_" + bones.size()), bones);
        names.put(uuid, name);
        addBone(bones, new Bone(name, parent, vector(group.get("origin"), Vector.ZERO), vector(group.get("rotation"), Vector.ZERO), Vector.ONE, null, null, 1, name.startsWith("mount_"), false));
        for (JsonElement child : array(group, "children")) outliner(id, model, child, name, cubes, textures, bones, assets, names, included);
    }

    private void cube(String id, JsonObject model, String uuid, JsonObject cube, String parent, Map<String, String> textures,
                       Map<String, Bone> bones, Map<String, byte[]> assets, Map<String, String> names) {
        String name = unique(text(cube, "name", "cube_" + bones.size()), bones);
        names.put(uuid, name);
        if (text(cube, "type", "cube").equals("locator")) {
            Vector pivot = vector(cube.has("position") ? cube.get("position") : cube.get("from"), parent == null ? Vector.ZERO : bones.get(parent).pivot());
            addBone(bones, new Bone(name, parent, pivot, vector(cube.get("rotation"), Vector.ZERO), Vector.ONE, null, null, 1, false, false, ModelDefinition.Role.LOCATOR_PIVOT));
            return;
        }
        Vector pivot = vector(cube.get("origin"), parent == null ? Vector.ZERO : bones.get(parent).pivot());
        Vector from = vector(cube.get("from"), Vector.ZERO).subtract(pivot);
        Vector to = vector(cube.get("to"), Vector.ONE).subtract(pivot);
        float inflate = (float) decimal(cube, "inflate", 0);
        from = from.subtract(new Vector(inflate, inflate, inflate));
        to = to.add(new Vector(inflate, inflate, inflate));
        float extent = Math.max(Math.max(Math.max(Math.abs(from.x()), Math.abs(from.y())), Math.abs(from.z())), Math.max(Math.max(Math.abs(to.x()), Math.abs(to.y())), Math.abs(to.z())));
        float geometryScale = Math.max(1, extent / 23f);
        from = from.multiply(1 / geometryScale).add(new Vector(8, 8, 8));
        to = to.multiply(1 / geometryScale).add(new Vector(8, 8, 8));
        Map<String, Object> faces = new TreeMap<>();
        JsonObject resolution = object(model, "resolution");
        double textureWidth = decimal(resolution, "width", 16);
        double textureHeight = decimal(resolution, "height", 16);
        if (textureWidth <= 0 || textureHeight <= 0) throw new IllegalArgumentException("Invalid Blockbench texture resolution.");
        for (var face : object(cube, "faces").entrySet()) {
            if (!Set.of("north", "south", "east", "west", "up", "down").contains(face.getKey())) throw new IllegalArgumentException("Unknown cube face.");
            JsonObject values = face.getValue().getAsJsonObject();
            if (!values.has("texture") || values.get("texture").isJsonNull()) continue;
            String texture = values.get("texture").getAsString();
            if (!textures.containsKey(texture)) throw new IllegalArgumentException("Cube references unknown texture index: " + texture);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("texture", "#" + texture);
            output.put("tintindex", 0);
            JsonArray uv = array(values, "uv");
            if (uv.size() != 4) throw new IllegalArgumentException("A Blockbench cube face needs four UV coordinates.");
            output.put("uv", List.of(uv.get(0).getAsDouble() * 16 / textureWidth, uv.get(1).getAsDouble() * 16 / textureHeight,
                    uv.get(2).getAsDouble() * 16 / textureWidth, uv.get(3).getAsDouble() * 16 / textureHeight));
            int rotation = (int) decimal(values, "rotation", 0);
            if (rotation % 90 != 0) throw new IllegalArgumentException("Cube face UV rotation must use 90-degree steps.");
            if (rotation != 0) output.put("rotation", Math.floorMod(rotation, 360));
            faces.put(face.getKey(), output);
        }
        ContentId content = ContentId.parse(id);
        String generated = content.namespace() + ":blocksadder_models/" + content.value() + "/bone_" + bones.size();
        String value = generated.substring(generated.indexOf(':') + 1);
        Map<String, Object> geometry = Map.of("textures", textures, "elements", List.of(Map.of("from", numbers(from), "to", numbers(to), "faces", faces)),
                "display", Map.of("fixed", Map.of("rotation", List.of(0, 0, 0), "translation", List.of(0, 0, 0), "scale", List.of(1, 1, 1))));
        assets.put("assets/" + content.namespace() + "/models/" + value + ".json", Json.write(geometry).getBytes(StandardCharsets.UTF_8));
        assets.put("assets/" + content.namespace() + "/items/" + value + ".json", Json.write(Map.of("model", Map.of("type", "minecraft:model", "model", generated,
                "tints", List.of(Map.of("type", "minecraft:custom_model_data", "index", 0, "default", 16777215))))).getBytes(StandardCharsets.UTF_8));
        addBone(bones, new Bone(name, parent, pivot, vector(cube.get("rotation"), Vector.ZERO), Vector.ONE, null, generated, geometryScale, false, false));
    }

    private static List<Float> numbers(Vector value) { return List.of(value.x(), value.y(), value.z()); }

    private static Object field(Map<?, ?> entry, String key) { return entry.containsKey(key) ? entry.get(key) : entry.get(key.replace('-', '_')); }

    private static ModelDefinition.Value animatedVector(Object configured, Vector fallback) {
        List<Object> values = new ArrayList<>();
        if (configured instanceof JsonObject object) {
            for (String axis : List.of("x", "y", "z")) values.add(object.get(axis));
        } else if (configured instanceof JsonElement element && element.isJsonObject()) {
            for (String axis : List.of("x", "y", "z")) values.add(element.getAsJsonObject().get(axis));
        } else if (configured instanceof JsonArray array) array.forEach(values::add);
        else if (configured instanceof List<?> list) values.addAll(list);
        else if (configured == null) return new ModelDefinition.Value(fallback, List.of());
        else throw new IllegalArgumentException("Animated model vectors require three numbers or Molang expressions.");
        if (values.size() != 3) throw new IllegalArgumentException("Animated model vectors require three values.");
        float[] defaults = {fallback.x(), fallback.y(), fallback.z()};
        float[] constants = defaults.clone();
        List<String> expressions = new ArrayList<>();
        boolean dynamic = false;
        for (int axis = 0; axis < 3; axis++) {
            Object value = values.get(axis);
            String input = value == null || value instanceof JsonElement element && element.isJsonNull() ? String.valueOf(defaults[axis])
                    : value instanceof JsonElement element ? element.getAsString() : String.valueOf(value);
            try {
                constants[axis] = Float.parseFloat(input);
                if (!Float.isFinite(constants[axis])) throw new IllegalArgumentException("Model values must be finite.");
            } catch (NumberFormatException exception) { dynamic = true; }
            expressions.add(input);
        }
        return new ModelDefinition.Value(new Vector(constants[0], constants[1], constants[2]), dynamic ? expressions.stream().map(Molang::compile).toList() : List.of());
    }

    private static Vector vector(Object configured, Vector fallback) {
        if (configured == null) return fallback;
        if (configured instanceof JsonArray array) {
            if (array.size() != 3) throw new IllegalArgumentException("A model vector needs three values.");
            return new Vector(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
        }
        if (configured instanceof List<?> values && values.size() == 3) return new Vector(number(values.get(0), 0), number(values.get(1), 0), number(values.get(2), 0));
        throw new IllegalArgumentException("A model vector needs three numeric values.");
    }

    private static float number(Object value, float fallback) {
        if (value == null) return fallback;
        if (value instanceof Number number && Float.isFinite(number.floatValue())) return number.floatValue();
        throw new IllegalArgumentException("Model animation values must be finite numbers; expressions are unsupported.");
    }

    private static float bounded(double value, double min, double max, String field) {
        if (!Double.isFinite(value) || value < min || value > max) throw new IllegalArgumentException(field + " must be between " + min + " and " + max + ".");
        return (float) value;
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? new JsonArray() : value.getAsJsonArray();
    }

    private static JsonObject object(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? new JsonObject() : value.getAsJsonObject();
    }

    private static String text(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static double decimal(JsonObject object, String name, double fallback) {
        JsonElement value = object.get(name);
        double number = value == null || value.isJsonNull() ? fallback : value.getAsDouble();
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Model values must be finite numbers.");
        return number;
    }

    private static String unique(String requested, Map<String, Bone> bones) {
        String name = requested;
        int suffix = 1;
        while (bones.containsKey(name)) name = requested + "_" + suffix++;
        return name;
    }

    private static void addBone(Map<String, Bone> bones, Bone bone) {
        if (bone.name().isBlank() || bone.name().length() > 128 || bones.putIfAbsent(bone.name(), bone) != null) throw new IllegalArgumentException("Invalid or duplicate bone name: " + bone.name());
    }

    private static List<Keyframe> sorted(List<Keyframe> frames) {
        if (frames.size() > 4096) throw new IllegalArgumentException("A model animation channel may have at most 4096 keyframes.");
        frames.sort(Comparator.comparingDouble(Keyframe::time));
        float previous = -1;
        for (Keyframe frame : frames) {
            if (!Float.isFinite(frame.time()) || frame.time() < 0 || frame.time() == previous) throw new IllegalArgumentException("Animation keyframe times must be unique, finite and nonnegative.");
            previous = frame.time();
        }
        return List.copyOf(frames);
    }

    private static String interpolation(String input) {
        String mode = input.toLowerCase(Locale.ROOT);
        if (!Set.of("linear", "step", "catmullrom", "bezier").contains(mode)) throw new IllegalArgumentException("Unsupported animation interpolation: " + input);
        return mode;
    }

    private static ModelDefinition.Role role(String input) {
        return switch (input.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT)) {
            case "", "normal" -> ModelDefinition.Role.NORMAL;
            case "head" -> ModelDefinition.Role.HEAD;
            case "hatpivot", "hat" -> ModelDefinition.Role.HAT_PIVOT;
            case "lefthandpivot", "lefthand" -> ModelDefinition.Role.LEFT_HAND_PIVOT;
            case "righthandpivot", "righthand" -> ModelDefinition.Role.RIGHT_HAND_PIVOT;
            case "mountpivot", "mount" -> ModelDefinition.Role.MOUNT_PIVOT;
            case "locatorpivot", "locator" -> ModelDefinition.Role.LOCATOR_PIVOT;
            default -> throw new IllegalArgumentException("Unknown model bone role: " + input);
        };
    }

    private static String loopMode(String input) {
        String loop = input.toLowerCase(Locale.ROOT);
        if (!Set.of("once", "loop", "hold").contains(loop)) throw new IllegalArgumentException("Animation loop mode must be once, loop or hold.");
        return loop;
    }

    private static void validateTimes(Map<String, Track> tracks, float duration) {
        for (Track track : tracks.values()) for (List<Keyframe> channel : List.of(track.position(), track.rotation(), track.scale())) if (!channel.isEmpty() && channel.getLast().time() > duration + 0.001f) throw new IllegalArgumentException("Animation keyframe extends past its duration.");
    }

    private static void order(Bone bone, Map<String, Bone> bones, Set<String> visiting, Set<String> visited, List<Bone> result) {
        if (visited.contains(bone.name())) return;
        if (!visiting.add(bone.name())) throw new IllegalArgumentException("Cyclic model bone parent: " + bone.name());
        if (bone.parent() != null) {
            Bone parent = bones.get(bone.parent());
            if (parent == null) throw new IllegalArgumentException("Unknown model bone parent: " + bone.parent());
            order(parent, bones, visiting, visited, result);
        }
        visiting.remove(bone.name());
        visited.add(bone.name());
        result.add(bone);
    }
}
