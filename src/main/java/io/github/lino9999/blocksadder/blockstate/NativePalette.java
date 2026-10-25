package io.github.lino9999.blocksadder.blockstate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class NativePalette {
    private static final List<String> INSTRUMENTS = List.of("harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling");
    private static final String[] FACES = {"west", "up", "south", "north", "east", "down"};

    private NativePalette() {}

    public static List<Integer> slots(NativeBlockMode mode) {
        List<Integer> result = new ArrayList<>();
        switch (mode) {
            case REAL -> { for (int i = 0; i < 192; i++) if (i % 64 != 63) result.add(i); }
            case REAL_TRANSPARENT -> { for (int i = 193; i <= 254; i++) result.add(i); }
            case REAL_NOTE -> { for (int i = 256; i <= 1005; i++) result.add(i); }
            case REAL_WIRE -> { for (int i = 1008; i <= 1134; i++) result.add(i); }
            default -> {}
        }
        return List.copyOf(result);
    }

    public static State state(NativeBlockMode mode, int slot) {
        if (!slots(mode).contains(slot)) throw new IllegalArgumentException("Invalid " + mode + " native block slot: " + slot);
        Map<String, String> properties = new TreeMap<>();
        String material;
        switch (mode) {
            case REAL, REAL_TRANSPARENT -> {
                material = mode == NativeBlockMode.REAL_TRANSPARENT ? "chorus_plant" : switch (slot / 64) {
                    case 0 -> "brown_mushroom_block";
                    case 1 -> "red_mushroom_block";
                    default -> "mushroom_stem";
                };
                for (int bit = 0; bit < FACES.length; bit++) properties.put(FACES[bit], Boolean.toString((slot & 1 << bit) != 0));
            }
            case REAL_NOTE -> {
                material = "note_block";
                int local = slot - 256;
                properties.put("powered", Boolean.toString(local >= 375));
                local = local % 375 + 25;
                properties.put("instrument", INSTRUMENTS.get(local / 25));
                properties.put("note", Integer.toString(local % 25));
            }
            case REAL_WIRE -> {
                material = "tripwire";
                int bits = slot - 1007;
                String[] names = {"powered", "west", "disarmed", "south", "north", "east", "attached"};
                for (int bit = 0; bit < names.length; bit++) properties.put(names[bit], Boolean.toString((bits & 1 << bit) != 0));
            }
            default -> throw new IllegalArgumentException("Display modes do not have native block states.");
        }
        return new State("minecraft:" + material, properties);
    }

    public record State(String material, Map<String, String> properties) {
        public State {
            properties = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        }

        public String data() {
            return material + "[" + properties.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(java.util.stream.Collectors.joining(",")) + "]";
        }
    }
}
