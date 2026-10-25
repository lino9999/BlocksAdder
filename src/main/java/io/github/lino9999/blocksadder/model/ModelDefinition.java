package io.github.lino9999.blocksadder.model;

import java.util.List;
import java.util.Map;

public record ModelDefinition(String id, float scale, float viewRange, boolean hideBase, boolean selfVisible,
                              float hitboxWidth, float hitboxHeight, List<Bone> bones, Map<String, Animation> animations,
                              Map<String, byte[]> assets) {
    public ModelDefinition {
        bones = List.copyOf(bones);
        animations = Map.copyOf(animations);
        assets = Map.copyOf(assets);
    }

    public boolean hasExpressions() {
        for (Animation animation : animations.values()) for (ModelEffect effect : animation.effects()) if (effect.script() != null) return true;
        for (Animation animation : animations.values()) for (Track track : animation.tracks().values()) for (List<Keyframe> frames : List.of(track.position(), track.rotation(), track.scale()))
            for (Keyframe frame : frames) if (!frame.before().expressions().isEmpty() || !frame.after().expressions().isEmpty()) return true;
        return false;
    }

    public record Vector(float x, float y, float z) {
        public static final Vector ZERO = new Vector(0, 0, 0);
        public static final Vector ONE = new Vector(1, 1, 1);

        public Vector {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) throw new IllegalArgumentException("Model vectors must contain finite numbers.");
        }

        public Vector subtract(Vector other) { return new Vector(x - other.x, y - other.y, z - other.z); }
        public Vector add(Vector other) { return new Vector(x + other.x, y + other.y, z + other.z); }
        public Vector multiply(float scalar) { return new Vector(x * scalar, y * scalar, z * scalar); }
    }

    public enum Role { NORMAL, HEAD, HAT_PIVOT, LEFT_HAND_PIVOT, RIGHT_HAND_PIVOT, MOUNT_PIVOT, LOCATOR_PIVOT }

    public record Bone(String name, String parent, Vector pivot, Vector rotation, Vector scale, String item,
                       String generatedModel, float geometryScale, boolean mount, boolean absolutePose, Role role,
                       float maxHeadPitch, float maxHeadYaw) {
        public Bone {
            if (!Float.isFinite(maxHeadPitch) || !Float.isFinite(maxHeadYaw) || maxHeadPitch < 0 || maxHeadPitch > 180 || maxHeadYaw < 0 || maxHeadYaw > 180) throw new IllegalArgumentException("Head rotation limits must be between zero and 180 degrees.");
        }
        public Bone(String name, String parent, Vector pivot, Vector rotation, Vector scale, String item,
                    String generatedModel, float geometryScale, boolean mount, boolean absolutePose) {
            this(name, parent, pivot, rotation, scale, item, generatedModel, geometryScale, mount, absolutePose, mount ? Role.MOUNT_PIVOT : Role.NORMAL, 70, 45);
        }
        public Bone(String name, String parent, Vector pivot, Vector rotation, Vector scale, String item,
                    String generatedModel, float geometryScale, boolean mount, boolean absolutePose, Role role) {
            this(name, parent, pivot, rotation, scale, item, generatedModel, geometryScale, mount, absolutePose, role, 70, 45);
        }
    }

    public record Value(Vector constant, List<Molang.Expression> expressions) {
        public Value { expressions = List.copyOf(expressions); if (!expressions.isEmpty() && expressions.size() != 3) throw new IllegalArgumentException("Animated model values require three expressions."); }
        public Vector sample(Molang.Context context, Vector previous) {
            if (expressions.isEmpty()) return constant;
            return new Vector((float) expressions.get(0).evaluate(context.withThis(previous.x())),
                    (float) expressions.get(1).evaluate(context.withThis(previous.y())),
                    (float) expressions.get(2).evaluate(context.withThis(previous.z())));
        }
    }

    public record Bezier(Vector leftTime, Vector leftValue, Vector rightTime, Vector rightValue) {
        public static final Bezier ZERO = new Bezier(Vector.ZERO, Vector.ZERO, Vector.ZERO, Vector.ZERO);
    }

    public record Keyframe(float time, Vector value, String interpolation, Value before, Value after, Bezier bezier) {
        public Keyframe(float time, Vector value, String interpolation) {
            this(time, value, interpolation, new Value(value, List.of()), new Value(value, List.of()), Bezier.ZERO);
        }
    }
    public record Track(List<Keyframe> position, List<Keyframe> rotation, List<Keyframe> scale) {
        public Track {
            position = List.copyOf(position);
            rotation = List.copyOf(rotation);
            scale = List.copyOf(scale);
        }
    }

    public record Animation(String name, float length, String loopMode, float loopStart, float loopEnd, Map<String, Track> tracks,
                            List<ModelEffect> effects) {
        public Animation { tracks = Map.copyOf(tracks); effects = List.copyOf(effects); }
        public Animation(String name, float length, String loopMode, float loopStart, float loopEnd, Map<String, Track> tracks) {
            this(name, length, loopMode, loopStart, loopEnd, tracks, List.of());
        }
    }
}
