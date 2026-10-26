package io.github.lino9999.blocksadder.model;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;

public final class PlayerSkinMath {
    public static final float MARKER_X = 4096;
    public static final float MARKER_STEP = 1024;
    public static final float MARKER_WINDOW = 384;
    private PlayerSkinMath() {}

    public static Matrix4f transform(Matrix4fc bone, PlayerSkinPart part, boolean slim, boolean outer) {
        Vector3f position = bone.getTranslation(new Vector3f());
        Vector3f scale = bone.getScale(new Vector3f());
        if (!position.isFinite() || !scale.isFinite() || Math.abs(position.x) > 64 || Math.abs(position.y) > 64 || Math.abs(position.z) > 64
                || scale.x > 64 || scale.y > 64 || scale.z > 64) throw new IllegalArgumentException("Live player-skin bones must stay within 64 blocks of their target and use an effective scale no greater than 64.");
        Matrix4f result = new Matrix4f(bone).translate(part.offset(slim, outer)).scale(part.scale(slim, outer));
        return result.m30(result.m30() + MARKER_X).m31(result.m31() - MARKER_STEP * (part.code(slim, outer) + 1));
    }

    public static int marker(Vector3f point) {
        int code = (int) Math.floor((-point.y + MARKER_STEP / 2) / MARKER_STEP) - 1;
        return Math.abs(point.x - MARKER_X) <= MARKER_WINDOW && code >= 0 && code < 24
                && Math.abs(point.y + MARKER_STEP * (code + 1)) <= MARKER_WINDOW ? code : -1;
    }

    public static Vector3f restore(Vector3f point) {
        int code = marker(point);
        return code < 0 ? new Vector3f(point) : new Vector3f(point.x - MARKER_X, point.y + MARKER_STEP * (code + 1), point.z);
    }

    public static Vector2f uv(PlayerSkinPart part, boolean slim, boolean outer, float u, float v) {
        float x = u * 64;
        float y = v * 64;
        if (x < 0 || y < 0 || x > 32 || y > 16 || y < 8 && (x < 8 || x > 24)) throw new IllegalArgumentException("Skin remapping requires an inner native head-face UV.");
        int width = part.width(slim);
        int depth = part.depth();
        int height = part.height();
        float mappedU;
        float mappedV;
        if (y < 8) {
            boolean bottom = x >= 16;
            mappedU = depth + (bottom ? width : 0) + (x - (bottom ? 16 : 8)) / 8 * width;
            mappedV = y / 8 * depth;
        } else {
            int face = Math.min(3, (int) (x / 8));
            float start = switch (face) { case 0 -> 0; case 1 -> depth; case 2 -> depth + width; default -> 2 * depth + width; };
            mappedU = start + (x / 8 - face) * (face % 2 == 0 ? depth : width);
            mappedV = depth + (y - 8) / 8 * height;
        }
        return new Vector2f((mappedU + part.u(outer)) / 64, (mappedV + part.v(outer)) / 64);
    }
}
