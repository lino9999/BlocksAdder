package io.github.lino9999.blocksadder.integration.protocol;

import com.comphenix.protocol.wrappers.nbt.NbtBase;
import com.comphenix.protocol.wrappers.nbt.NbtCompound;
import com.comphenix.protocol.wrappers.nbt.NbtFactory;
import com.comphenix.protocol.wrappers.nbt.NbtType;
import io.github.lino9999.blocksadder.integration.BlockNbtConditions;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import com.comphenix.protocol.reflect.FuzzyReflection;
import com.comphenix.protocol.reflect.accessors.Accessors;
import com.comphenix.protocol.reflect.accessors.FieldAccessor;
import com.comphenix.protocol.reflect.accessors.MethodAccessor;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.utility.MinecraftRegistryAccess;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ProtocolBlockNbt {
    private record Reader(FieldAccessor field, List<MethodAccessor> serializers) {}
    private static final Map<Class<?>, Reader> READERS = new ConcurrentHashMap<>();
    private ProtocolBlockNbt() {}

    public static BlockNbtConditions.Snapshot read(Block block) {
        NbtCompound snapshot = NbtFactory.readBlockState(block);
        if (snapshot == null && block.getState() instanceof TileState state) snapshot = readInherited(state);
        NbtCompound captured = snapshot;
        return condition -> matches(captured, condition);
    }

    private static NbtCompound readInherited(TileState state) {
        Reader reader = READERS.computeIfAbsent(state.getClass(), ProtocolBlockNbt::reader);
        Object value = reader.field.get(state);
        Object registry = MinecraftRegistryAccess.get();
        NbtCompound result = null;
        for (MethodAccessor serializer : reader.serializers) {
            Object serialized = serializer.invoke(value, registry);
            if (serialized == null) continue;
            NbtCompound candidate = NbtFactory.fromNMSCompound(serialized);
            if (result == null || candidate.getKeys().containsAll(result.getKeys())) result = candidate;
        }
        return result;
    }

    private static Reader reader(Class<?> stateType) {
        Class<?> tile = MinecraftReflection.getTileEntityClass();
        FieldAccessor field = null;
        for (Class<?> current = stateType; current != null && field == null; current = current.getSuperclass()) {
            for (var candidate : current.getDeclaredFields()) {
                if (!Modifier.isStatic(candidate.getModifiers()) && candidate.getType() == tile) { field = Accessors.getFieldAccessor(candidate); break; }
            }
        }
        if (field == null) throw new IllegalStateException("ProtocolLib cannot locate the inherited tile snapshot.");
        Class<?> compound = MinecraftReflection.getNBTCompoundClass();
        Class<?> lookup = MinecraftReflection.getHolderLookupProviderClass();
        List<MethodAccessor> serializers = FuzzyReflection.fromClass(tile, true).getMethodListByParameters(compound, lookup).stream()
                .filter(method -> Modifier.isPublic(method.getModifiers()) && Modifier.isFinal(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()))
                .map(Accessors::getMethodAccessor).toList();
        if (serializers.isEmpty() || serializers.size() > 8) throw new IllegalStateException("ProtocolLib tile snapshot serializers are unsupported on this server.");
        return new Reader(field, serializers);
    }

    private static boolean matches(NbtCompound snapshot, BlockNbtConditions.Condition condition) {
        if (snapshot == null) return false;
        NbtBase<?> node = snapshot;
        for (String part : condition.path()) {
            if (!(node instanceof NbtCompound compound) || !compound.containsKey(part)) return false;
            node = compound.getValue(part);
            if (node == null) return false;
        }
        NbtType expected = NbtType.valueOf("TAG_" + condition.type().toUpperCase(java.util.Locale.ROOT));
        return node.getType() == expected && condition.expected().equals(node.getValue());
    }
}
