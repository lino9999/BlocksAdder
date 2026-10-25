package io.github.lino9999.blocksadder.model;

import io.github.lino9999.blocksadder.model.ModelDefinition.Keyframe;
import io.github.lino9999.blocksadder.model.ModelDefinition.Vector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public final class AnimationMath {
    private AnimationMath() {}

    public static Vector sample(List<Keyframe> frames, float time, Vector fallback) {
        return sample(frames, time, fallback, Molang.Context.time(time), fallback);
    }

    public static Vector sample(List<Keyframe> frames, float time, Vector fallback, Molang.Context context, Vector previous) {
        if (frames.isEmpty()) return fallback;
        if (time <= frames.getFirst().time()) return frames.getFirst().before().sample(context, previous);
        if (time >= frames.getLast().time()) return (time == frames.getLast().time() ? frames.getLast().before() : frames.getLast().after()).sample(context, previous);
        int low = 0;
        int high = frames.size() - 1;
        while (low + 1 < high) {
            int middle = (low + high) >>> 1;
            if (frames.get(middle).time() <= time) low = middle;
            else high = middle;
        }
        Keyframe first = frames.get(low);
        Keyframe second = frames.get(high);
        if (time == first.time()) return first.before().sample(context, previous);
        Vector from = first.after().sample(context, previous);
        Vector to = second.before().sample(context, previous);
        float interval = second.time() - first.time();
        float progress = interval <= 0 ? 1 : Math.clamp((time - first.time()) / interval, 0, 1);
        if (first.interpolation().equals("step")) return from;
        if (first.interpolation().equals("catmullrom") || second.interpolation().equals("catmullrom")) {
            Vector before = frames.get(Math.max(0, low - 1)).after().sample(context, previous);
            Vector next = frames.get(Math.min(frames.size() - 1, high + 1)).before().sample(context, previous);
            return new Vector(cubic(before.x(), from.x(), to.x(), next.x(), progress),
                    cubic(before.y(), from.y(), to.y(), next.y(), progress),
                    cubic(before.z(), from.z(), to.z(), next.z(), progress));
        }
        if (first.interpolation().equals("bezier") || second.interpolation().equals("bezier")) {
            var right = first.bezier();
            var left = second.bezier();
            return new Vector(bezier(from.x(), to.x(), right.rightTime().x(), right.rightValue().x(), left.leftTime().x(), left.leftValue().x(), interval, progress),
                    bezier(from.y(), to.y(), right.rightTime().y(), right.rightValue().y(), left.leftTime().y(), left.leftValue().y(), interval, progress),
                    bezier(from.z(), to.z(), right.rightTime().z(), right.rightValue().z(), left.leftTime().z(), left.leftValue().z(), interval, progress));
        }
        return from.multiply(1 - progress).add(to.multiply(progress));
    }

    private static float bezier(float first, float second, float rightTime, float rightValue, float leftTime, float leftValue, float interval, float progress) {
        double firstTime = Math.clamp(rightTime / interval, 0, 1);
        double secondTime = 1 + Math.clamp(leftTime / interval, -1, 0);
        double low = 0;
        double high = 1;
        for (int step = 0; step < 24; step++) {
            double middle = (low + high) / 2;
            if (curve(0, firstTime, secondTime, 1, middle) < progress) low = middle;
            else high = middle;
        }
        return (float) curve(first, first + rightValue, second + leftValue, second, (low + high) / 2);
    }

    private static double curve(double first, double right, double left, double second, double progress) {
        double inverse = 1 - progress;
        return inverse * inverse * inverse * first + 3 * inverse * inverse * progress * right + 3 * inverse * progress * progress * left + progress * progress * progress * second;
    }

    private static float cubic(float previous, float first, float second, float next, float progress) {
        return 0.5f * ((2 * first) + (-previous + second) * progress
                + (2 * previous - 5 * first + 4 * second - next) * progress * progress
                + (-previous + 3 * first - 3 * second + next) * progress * progress * progress);
    }

    public static Quaternionf rotation(Vector value) {
        float radians = (float) (Math.PI / 180);
        return new Quaternionf().rotationXYZ(value.x() * radians, value.y() * radians, value.z() * radians);
    }

    public static Matrix4f local(ModelDefinition.Bone bone, Vector parentPivot, ModelDefinition.Track track, float time) {
        return local(bone, parentPivot, track, time, Molang.Context.time(time), new HashMap<>());
    }

    public static Matrix4f local(ModelDefinition.Bone bone, Vector parentPivot, ModelDefinition.Track track, float time,
                               Molang.Context context, Map<String, Vector> history) {
        if (bone.absolutePose()) {
            Vector position = channel(bone, "position", track == null ? List.of() : track.position(), time, bone.pivot(), context, history).multiply(1f / 16);
            Vector rotation = channel(bone, "rotation", track == null ? List.of() : track.rotation(), time, bone.rotation(), context, history);
            Vector scale = channel(bone, "scale", track == null ? List.of() : track.scale(), time, Vector.ONE, context, history);
            float radians = (float) (Math.PI / 180);
            return new Matrix4f().translation(position.x(), position.y(), position.z())
                    .rotate(new Quaternionf().rotationYXZ(rotation.x() * radians, rotation.y() * radians, rotation.z() * radians))
                    .scale(bone.scale().x() * scale.x(), bone.scale().y() * scale.y(), bone.scale().z() * scale.z());
        }
        Vector position = channel(bone, "position", track == null ? List.of() : track.position(), time, Vector.ZERO, context, history);
        Vector rotation = channel(bone, "rotation", track == null ? List.of() : track.rotation(), time, Vector.ZERO, context, history);
        Vector scale = channel(bone, "scale", track == null ? List.of() : track.scale(), time, Vector.ONE, context, history);
        Vector pivot = bone.pivot().subtract(parentPivot).add(position).multiply(1f / 16);
        return new Matrix4f().translation(pivot.x(), pivot.y(), pivot.z()).rotate(rotation(bone.rotation()))
                .rotate(rotation(rotation)).scale(bone.scale().x() * scale.x(), bone.scale().y() * scale.y(), bone.scale().z() * scale.z());
    }

    private static Vector channel(ModelDefinition.Bone bone, String channel, List<Keyframe> frames, float time, Vector fallback,
                                  Molang.Context context, Map<String, Vector> history) {
        String key = bone.name() + "/" + channel;
        Vector value = sample(frames, time, fallback, context, history.getOrDefault(key, fallback));
        history.put(key, value);
        return value;
    }
}
