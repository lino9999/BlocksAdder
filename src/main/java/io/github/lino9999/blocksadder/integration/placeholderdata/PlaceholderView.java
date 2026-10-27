package io.github.lino9999.blocksadder.integration.placeholderdata;

import io.github.lino9999.blocksadder.content.ContentId;
import io.github.lino9999.blocksadder.content.ContentRegistry;
import io.github.lino9999.blocksadder.pack.HudSpaceFont;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public final class PlaceholderView {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_][a-z0-9_.-]*");
    private static final Pattern KEY = Pattern.compile("[a-z0-9_][a-z0-9_./-]*");
    private static final int MAX_PENDING = 512;
    private static final int MAX_ALIASES = 4096;
    private static final int MAX_OWNER_ALIASES = 64;
    private final Map<String, Glyph> glyphs;
    private final Catalog glyphIds;
    private final Catalog meters;
    private volatile State active = new State();

    public PlaceholderView(ContentRegistry registry) {
        Map<String, Glyph> copied = new HashMap<>();
        registry.sections("font_images").forEach((id, source) -> {
            ContentId.parse(id);
            Object raw = source.get("character");
            if (!(raw instanceof String character) || character.isEmpty() || character.codePointCount(0, character.length()) != 1)
                throw new IllegalArgumentException("Placeholder glyph " + id + " requires one Unicode character.");
            int codepoint = character.codePointAt(0);
            if (Character.isISOControl(codepoint) || codepoint >= 0xD800 && codepoint <= 0xDFFF)
                throw new IllegalArgumentException("Placeholder glyph " + id + " contains an invalid character.");
            String font = "minecraft:default";
            if (source.contains("font")) {
                if (!(source.get("font") instanceof String name)) throw new IllegalArgumentException("Placeholder glyph font must be a namespaced identifier.");
                font = name;
            }
            ContentId.parse(font);
            copied.put(id, new Glyph(character, font));
        });
        glyphs = Map.copyOf(copied);
        glyphIds = new Catalog(glyphs.keySet());
        Set<String> meterIds = registry.sections("stats").keySet();
        meterIds.forEach(ContentId::parse);
        meters = new Catalog(meterIds);
    }

    public void player(Object identity, Map<String, Double> values) {
        State state = active;
        if (state == null) return;
        if (identity == null || values == null) throw new IllegalArgumentException("Placeholder player snapshots require an identity and values.");
        Map<String, Double> copied = new HashMap<>();
        for (var entry : values.entrySet()) {
            if (entry.getKey() == null || !meters.ids.contains(entry.getKey())) continue;
            Double value = entry.getValue();
            if (value == null || !Double.isFinite(value)) throw new IllegalArgumentException("Placeholder meter snapshots require finite numbers.");
            copied.put(entry.getKey(), value);
        }
        Identity key = new Identity(identity);
        Owner owner = state.primaries.get(key);
        if (owner != null && owner.live) {
            owner.values = new ConcurrentHashMap<>(copied);
            return;
        }
        Owner previous = state.contexts.get(key);
        if (previous != null) detach(state, previous, key);
        Owner created = new Owner(copied);
        state.primaries.put(key, created);
        state.contexts.put(key, created);
    }

    public void value(Object context, String requested, double value) {
        State state = active;
        if (state == null || context == null || requested == null) return;
        String id = meters.resolve(requested);
        if (id == null) return;
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Placeholder meter snapshots require finite numbers.");
        Owner owner = state.contexts.get(new Identity(context));
        if (owner != null && owner.live) owner.values.put(id, value);
    }

    public void remove(Object primary) {
        State state = active;
        if (state == null || primary == null) return;
        Identity key = new Identity(primary);
        Owner owner = state.primaries.remove(key);
        if (owner == null) return;
        owner.live = false;
        state.contexts.remove(key, owner);
        for (Identity alias : owner.aliases) detach(state, owner, alias);
        owner.values.clear();
    }

    public boolean contains(Object context) {
        State state = active;
        if (state == null || context == null) return false;
        Owner owner = state.contexts.get(new Identity(context));
        return owner != null && owner.live && active == state;
    }

    public String meterId(String params) {
        if (active == null || params == null || params.length() > 1024 || !params.startsWith("meter_")) return null;
        String selector = params.substring(6);
        return valid(selector) ? meters.resolve(selector) : null;
    }

    public String request(Object context, String params) {
        State state = active;
        if (state == null || params == null || params.length() > 1024) return null;
        int separator = params.indexOf('_');
        if (separator < 1) return null;
        String family = params.substring(0, separator);
        String selector = params.substring(separator + 1);
        if (selector.isEmpty() || selector.length() > 512) return null;
        String result;
        if (family.equals("move")) {
            int first = selector.charAt(0) == '+' || selector.charAt(0) == '-' ? 1 : 0;
            if (first == selector.length()) return null;
            for (int index = first; index < selector.length(); index++) if (selector.charAt(index) < '0' || selector.charAt(index) > '9') return null;
            try {
                int pixels = Integer.parseInt(selector);
                if (pixels < -HudSpaceFont.MAX_SHIFT || pixels > HudSpaceFont.MAX_SHIFT) return null;
                result = HudSpaceFont.characters(pixels);
            } catch (NumberFormatException exception) { return null; }
        } else {
            if (!valid(selector)) return null;
            if (family.equals("meter")) {
                if (meters.ambiguous.contains(selector)) return null;
                String id = meters.resolve(selector);
                if (id == null || context == null) result = "0.0";
                else {
                    Identity key = new Identity(context);
                    Owner owner = state.contexts.get(key);
                    if (owner == null || !owner.live) {
                        pending(state, key);
                        result = "0.0";
                    } else {
                        Double number = owner.values.get(id);
                        result = owner.live ? Double.toString(number == null ? 0 : number) : "0.0";
                    }
                }
            } else if (family.equals("glyph") || family.equals("code") || family.equals("font")) {
                String id = glyphIds.resolve(selector);
                if (id == null) return null;
                Glyph glyph = glyphs.get(id);
                result = family.equals("font") ? glyph.font : family.equals("code") ? glyph.character : glyph.character + HudSpaceFont.characters(-1);
            } else return null;
        }
        return active == state ? result : null;
    }

    public List<Object> pendingContexts(int maximum) {
        State state = active;
        if (state == null || maximum <= 0) return List.of();
        List<Object> result = new ArrayList<>();
        for (int count = 0; count < Math.min(maximum, MAX_PENDING); count++) {
            Identity key = state.pendingQueue.poll();
            if (key == null) break;
            if (state.pending.remove(key) != null) state.pendingCount.decrementAndGet();
            Owner owner = state.contexts.get(key);
            if (owner == null || !owner.live) result.add(key.value);
        }
        return active == state ? List.copyOf(result) : List.of();
    }

    public void alias(Object unknown, Object livePrimary) {
        State state = active;
        if (state == null || unknown == null || livePrimary == null) return;
        Identity key = new Identity(unknown);
        if (state.primaries.containsKey(key)) return;
        Owner owner = state.primaries.get(new Identity(livePrimary));
        if (owner == null || !owner.live) return;
        Owner previous = state.contexts.get(key);
        if (previous == owner) return;
        if (previous != null) {
            if (previous.live) return;
            detach(state, previous, key);
        }
        if (!reserve(owner.aliasCount, MAX_OWNER_ALIASES)) return;
        if (!reserve(state.aliasCount, MAX_ALIASES)) { owner.aliasCount.decrementAndGet(); return; }
        if (state.contexts.putIfAbsent(key, owner) != null) {
            owner.aliasCount.decrementAndGet();
            state.aliasCount.decrementAndGet();
            return;
        }
        owner.aliases.add(key);
        if (!owner.live || active != state) detach(state, owner, key);
    }

    public void retire() {
        State state = active;
        active = null;
        if (state == null) return;
        for (Owner owner : state.primaries.values()) owner.live = false;
        state.primaries.clear();
        state.contexts.clear();
        state.pending.clear();
        state.pendingQueue.clear();
    }

    private static void pending(State state, Identity key) {
        if (!reserve(state.pendingCount, MAX_PENDING)) return;
        if (state.pending.putIfAbsent(key, Boolean.TRUE) != null) {
            state.pendingCount.decrementAndGet();
            return;
        }
        state.pendingQueue.add(key);
    }

    private static boolean reserve(AtomicInteger counter, int maximum) {
        for (;;) {
            int current = counter.get();
            if (current >= maximum) return false;
            if (counter.compareAndSet(current, current + 1)) return true;
        }
    }

    private static void detach(State state, Owner owner, Identity key) {
        state.contexts.remove(key, owner);
        if (owner.aliases.remove(key)) {
            owner.aliasCount.decrementAndGet();
            state.aliasCount.decrementAndGet();
        }
    }

    private static boolean valid(String selector) {
        if (selector == null || selector.isEmpty() || selector.length() > 512) return false;
        int colon = selector.indexOf(':');
        if (colon < 0) return KEY.matcher(selector).matches() && !selector.contains("..");
        return colon > 0 && selector.indexOf(':', colon + 1) < 0 && NAMESPACE.matcher(selector.substring(0, colon)).matches()
                && KEY.matcher(selector.substring(colon + 1)).matches() && !selector.substring(colon + 1).contains("..");
    }

    private record Glyph(String character, String font) {}

    private record Identity(Object value) {
        @Override public int hashCode() { return System.identityHashCode(value); }
        @Override public boolean equals(Object other) { return other instanceof Identity key && value == key.value; }
    }

    private static final class Catalog {
        private final Set<String> ids;
        private final Map<String, String> shortNames;
        private final Set<String> ambiguous;

        private Catalog(Set<String> source) {
            ids = Set.copyOf(source);
            Map<String, String> names = new HashMap<>();
            Set<String> duplicates = new HashSet<>();
            for (String id : ids) {
                String local = ContentId.parse(id).value();
                if (names.putIfAbsent(local, id) != null) duplicates.add(local);
            }
            duplicates.forEach(names::remove);
            shortNames = Map.copyOf(names);
            ambiguous = Set.copyOf(duplicates);
        }

        private String resolve(String requested) {
            return ids.contains(requested) ? requested : requested.indexOf(':') < 0 ? shortNames.get(requested) : null;
        }
    }

    private static final class Owner {
        private volatile Map<String, Double> values;
        private final Set<Identity> aliases = ConcurrentHashMap.newKeySet();
        private final AtomicInteger aliasCount = new AtomicInteger();
        private volatile boolean live = true;

        private Owner(Map<String, Double> values) {
            this.values = new ConcurrentHashMap<>(values);
        }
    }

    private static final class State {
        private final Map<Identity, Owner> primaries = new ConcurrentHashMap<>();
        private final Map<Identity, Owner> contexts = new ConcurrentHashMap<>();
        private final Map<Identity, Boolean> pending = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Identity> pendingQueue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger pendingCount = new AtomicInteger();
        private final AtomicInteger aliasCount = new AtomicInteger();
    }
}
