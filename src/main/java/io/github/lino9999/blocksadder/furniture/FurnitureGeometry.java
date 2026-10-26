package io.github.lino9999.blocksadder.furniture;

import org.bukkit.Location;
import org.bukkit.util.BoundingBox;
import java.util.ArrayList;
import java.util.List;

final class FurnitureGeometry {
    record Cell(int x, int y, int z) {}
    record Tile(double x, double y, double z, float width, float height) {}
    private FurnitureGeometry() {}

    static BoundingBox bounds(Location anchor, FurnitureDefinition definition, float yaw) {
        double radians = Math.toRadians(Math.round(yaw / 90f) * 90d);
        double sin = Math.sin(radians);
        double cos = Math.cos(radians);
        double x = anchor.getX() + definition.widthOffset() * cos - definition.lengthOffset() * sin;
        double z = anchor.getZ() + definition.widthOffset() * sin + definition.lengthOffset() * cos;
        double width = Math.abs(cos) * definition.width() + Math.abs(sin) * definition.length();
        double length = Math.abs(cos) * definition.length() + Math.abs(sin) * definition.width();
        double y = anchor.getY() + definition.heightOffset();
        return new BoundingBox(x - width / 2, y, z - length / 2, x + width / 2, y + definition.height(), z + length / 2);
    }

    static List<Cell> cells(Location anchor, FurnitureDefinition definition, float yaw) {
        BoundingBox box = bounds(anchor, definition, yaw);
        List<Cell> result = new ArrayList<>();
        if (definition.complexEntity() != null) {
            for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++)
                for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++)
                    for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                        double dx = Math.max(0, Math.min(x + 1, box.getMaxX()) - Math.max(x, box.getMinX()));
                        double dy = Math.max(0, Math.min(y + 1, box.getMaxY()) - Math.max(y, box.getMinY()));
                        double dz = Math.max(0, Math.min(z + 1, box.getMaxZ()) - Math.max(z, box.getMinZ()));
                        if (dx * dy * dz >= .3 - .000001) result.add(new Cell(x, y, z));
                    }
        } else {
            int minX = (int) Math.floor(box.getMinX() + .5);
            int minY = (int) Math.floor(box.getMinY() + .000001);
            int minZ = (int) Math.floor(box.getMinZ() + .5);
            int maxX = Math.max(minX, (int) Math.floor(box.getMaxX() - .5 + .000001));
            int maxY = Math.max(minY, (int) Math.ceil(box.getMaxY() - .000001) - 1);
            int maxZ = Math.max(minZ, (int) Math.floor(box.getMaxZ() - .5 + .000001));
            for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) result.add(new Cell(x, y, z));
        }
        if (result.size() > 4096) throw new IllegalArgumentException("Furniture footprint exceeds its bounded cell limit.");
        return List.copyOf(result);
    }

    static List<Tile> tiles(Location anchor, FurnitureDefinition definition, float yaw) {
        BoundingBox box = bounds(anchor, definition, yaw);
        double spanX = box.getWidthX();
        double spanZ = box.getWidthZ();
        double size = Math.min(spanX, spanZ);
        boolean alongX = spanX >= spanZ;
        double length = Math.max(spanX, spanZ);
        int count = Math.max(1, (int) Math.ceil(length / size));
        if (count > (definition.complexEntity() == null ? 401 : 1024)) throw new IllegalArgumentException("Furniture interaction strip limit exceeded.");
        List<Tile> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            double offset = count == 1 ? 0 : (length - size) * index / (count - 1);
            double x = box.getMinX() + size / 2 + (alongX ? offset : 0);
            double z = box.getMinZ() + size / 2 + (alongX ? 0 : offset);
            result.add(new Tile(x, box.getMinY(), z, (float) size, (float) box.getHeight()));
        }
        return List.copyOf(result);
    }
}
