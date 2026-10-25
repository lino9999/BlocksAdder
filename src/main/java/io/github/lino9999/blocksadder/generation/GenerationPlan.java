package io.github.lino9999.blocksadder.generation;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;

record GenerationPlan(List<Mutation> mutations) {
    GenerationPlan {
        mutations = List.copyOf(mutations);
    }

    String encode() {
        StringBuilder result = new StringBuilder("3\n");
        for (Mutation mutation : mutations) result.append(mutation.x()).append('|').append(mutation.y()).append('|').append(mutation.z()).append('|')
                .append(mutation.target()).append('|').append(mutation.before()).append('|').append(mutation.after()).append('|')
                .append(mutation.group()).append('|').append(mutation.rule()).append('|').append(mutation.custom() ? "C" : "V").append('|').append(mutation.face().name()).append('|').append(mutation.yaw()).append('\n');
        return result.toString();
    }

    static GenerationPlan decode(String value) {
        String[] lines = value.split("\n");
        if (lines.length == 0 || !(lines[0].equals("1") || lines[0].equals("2") || lines[0].equals("3")) || lines.length > 16385) throw new IllegalArgumentException("Unsupported saved generation plan.");
        boolean legacy = lines[0].equals("1");
        boolean oriented = lines[0].equals("3");
        List<Mutation> mutations = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            String[] fields = lines[index].split("\\|", -1);
            if (fields.length != (legacy ? 8 : oriented ? 11 : 9)) throw new IllegalArgumentException("Invalid saved generation mutation.");
            int x = Integer.parseInt(fields[0]);
            int y = Integer.parseInt(fields[1]);
            int z = Integer.parseInt(fields[2]);
            if (Math.abs((long) x) > 30000000 || Math.abs((long) z) > 30000000 || y < -2048 || y > 4096) throw new IllegalArgumentException("Generation position outside world bounds.");
            if (fields[3].isBlank() || fields[3].length() > 256 || fields[7].isBlank() || fields[7].length() > 256) throw new IllegalArgumentException("Invalid saved generation identifier.");
            String before = Bukkit.createBlockData(fields[4]).getAsString();
            String after = Bukkit.createBlockData(fields[5]).getAsString();
            if (!legacy && !fields[8].equals("C") && !fields[8].equals("V")) throw new IllegalArgumentException("Invalid saved generation target kind.");
            boolean custom = legacy ? org.bukkit.Material.matchMaterial(fields[3]) == null : fields[8].equals("C");
            BlockFace face = oriented ? BlockFace.valueOf(fields[9]) : BlockFace.UP;
            if (!List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST).contains(face)) throw new IllegalArgumentException("Invalid saved generation orientation.");
            float yaw = oriented ? Float.parseFloat(fields[10]) : 0;
            if (!Float.isFinite(yaw)) throw new IllegalArgumentException("Invalid saved generation yaw.");
            mutations.add(new Mutation(x, y, z, fields[3], before, after, fields[6], fields[7], custom, face, yaw));
        }
        return new GenerationPlan(mutations);
    }

    record Mutation(int x, int y, int z, String target, String before, String after, String group, String rule, boolean custom, BlockFace face, float yaw) {
        Mutation(int x, int y, int z, String target, String before, String after, String group, String rule, boolean custom) {
            this(x, y, z, target, before, after, group, rule, custom, BlockFace.UP, 0);
        }
    }
}
