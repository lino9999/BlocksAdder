package io.github.lino9999.blocksadder.pack;

import io.github.lino9999.blocksadder.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PackDelivery implements Listener {
    private static final String EXEMPT = "blocksadder.pack.exempt";
    private final Plugin plugin;
    private final PackDownloads downloads;
    private final Map<Player, Request> pending = new IdentityHashMap<>();
    private final Map<Player, Offers> connections = new IdentityHashMap<>();
    private final Map<Player, BukkitTask> cooldowns = new IdentityHashMap<>();

    PackDelivery(Plugin plugin, PackDownloads downloads) {
        this.plugin = plugin;
        this.downloads = downloads;
    }

    public PackDeliveryPolicy applying(Player player) {
        Request request = pending.get(player);
        return request != null && request.accepted ? request.policy : null;
    }

    void offer(Player player, PackDownloads.Revision revision, String url, boolean required, PackDeliveryPolicy policy, boolean self) {
        if (self && cooldowns.containsKey(player)) throw new IllegalArgumentException("Wait before requesting the pack again.");
        Offers offers = connections.computeIfAbsent(player, unused -> new Offers());
        if (offers.sending) throw new IllegalArgumentException("This player's pack request is already being sent.");
        offers.sending = true;
        try {
            send(player, revision, url, required, policy, self, offers);
        } finally {
            offers.sending = false;
        }
    }

    private void send(Player player, PackDownloads.Revision revision, String url, boolean required, PackDeliveryPolicy policy, boolean self, Offers offers) {
        UUID previous = offers.latest();
        PackArtifact pack = revision.artifact();
        String address = url + (url.contains("?") ? "&" : "?") + "v=" + pack.sha1();
        byte[] digest = HexFormat.of().parseHex(pack.sha1());
        boolean force = required && policy.kickOnDecline() && !player.hasPermission(EXEMPT);
        long epoch = offers.epoch;
        Request request = new Request(offers.next(), policy, downloads.retain(revision));
        try {
            player.addResourcePack(request.id, address, digest, "BlocksAdder custom content", force);
        } catch (RuntimeException failure) {
            request.release.run();
            throw failure;
        }
        offers.sent++;
        if (offers.epoch != epoch || connections.get(player) != offers) {
            request.release.run();
            if (player.isOnline()) player.removeResourcePack(request.id);
            if (previous != null && player.isOnline()) player.removeResourcePack(previous);
            return;
        }
        retire(player);
        pending.put(player, request);
        arm(player, request);
        if (self && policy.selfCooldownTicks() > 0) {
            cooldowns.put(player, Bukkit.getScheduler().runTaskLater(plugin, () -> cooldowns.remove(player), policy.selfCooldownTicks()));
        }
        if (previous != null) player.removeResourcePack(previous);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatus(PlayerResourcePackStatusEvent event) {
        Player player = event.getPlayer();
        Request request = pending.get(player);
        Offers offers = connections.get(player);
        if (offers != null && offers.superseded(event.getID())) {
            switch (event.getStatus()) {
                case ACCEPTED, DOWNLOADED, SUCCESSFULLY_LOADED -> player.removeResourcePack(event.getID());
                default -> { }
            }
            return;
        }
        if (request == null || !request.id.equals(event.getID())) return;
        switch (event.getStatus()) {
            case ACCEPTED -> accept(player, request);
            case SUCCESSFULLY_LOADED -> retire(player);
            case DECLINED -> finish(player, request.policy.kickOnDecline(), "This server requires the BlocksAdder content pack.");
            case FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL -> finish(player, request.policy.kickOnFailure(), "The BlocksAdder content pack could not be applied.");
            case DISCARDED -> finish(player, request.policy.kickOnDiscard(), "The BlocksAdder content pack request was discarded.");
            default -> { }
        }
    }

    private void accept(Player player, Request request) {
        if (request.accepted) return;
        request.accepted = true;
        request.expiry.cancel();
        arm(player, request);
        PackDeliveryPolicy policy = request.policy;
        if (policy.title().isEmpty() && policy.subtitle().isEmpty()) return;
        String title = Text.color(policy.title());
        String subtitle = Text.color(policy.subtitle());
        Runnable notice = () -> player.sendTitle(title, subtitle, 0, 23, 4);
        request.notice = Bukkit.getScheduler().runTaskTimer(plugin, notice, 20, 20);
        notice.run();
    }

    private void arm(Player player, Request request) {
        request.expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.get(player) == request) retire(player);
        }, request.policy.timeoutTicks());
    }

    private void finish(Player player, boolean kick, String reason) {
        retire(player);
        if (kick && !player.hasPermission(EXEMPT)) player.kickPlayer(reason);
    }

    private void retire(Player player) {
        Request request = pending.remove(player);
        if (request == null) return;
        request.expiry.cancel();
        if (request.notice != null) request.notice.cancel();
        request.release.run();
    }

    void reset() {
        connections.values().forEach(offers -> offers.epoch++);
        List.copyOf(pending.keySet()).forEach(this::retire);
        cooldowns.values().forEach(BukkitTask::cancel);
        cooldowns.clear();
    }

    void close() {
        reset();
        connections.forEach((player, offers) -> {
            UUID latest = offers.latest();
            if (latest != null && player.isOnline()) player.removeResourcePack(latest);
        });
        connections.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Offers offers = connections.get(player);
        if (offers != null) offers.epoch++;
        retire(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        retire(player);
        connections.remove(player);
        BukkitTask cooldown = cooldowns.remove(player);
        if (cooldown != null) cooldown.cancel();
    }

    private static final class Offers {
        final UUID base = UUID.randomUUID();
        long sent;
        long epoch;
        boolean sending;

        UUID next() { return id(sent); }
        UUID latest() { return sent == 0 ? null : id(sent - 1); }

        private UUID id(long offset) {
            return new UUID(base.getMostSignificantBits(), base.getLeastSignificantBits() ^ offset);
        }

        boolean superseded(UUID id) {
            if (sent < 2 || id.getMostSignificantBits() != base.getMostSignificantBits()) return false;
            long offset = id.getLeastSignificantBits() ^ base.getLeastSignificantBits();
            return offset >= 0 && offset < sent - 1;
        }
    }

    private static final class Request {
        final UUID id;
        final PackDeliveryPolicy policy;
        final Runnable release;
        boolean accepted;
        BukkitTask expiry;
        BukkitTask notice;

        Request(UUID id, PackDeliveryPolicy policy, Runnable release) {
            this.id = id;
            this.policy = policy;
            this.release = release;
        }
    }
}
