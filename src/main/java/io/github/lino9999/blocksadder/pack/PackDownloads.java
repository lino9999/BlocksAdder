package io.github.lino9999.blocksadder.pack;

import java.util.HashMap;
import java.util.Map;

final class PackDownloads {
    record Revision(PackArtifact artifact, byte[] bytes) {}
    private record Held(Revision revision, int requests) {}

    private volatile Revision current;
    private final Map<String, Held> active = new HashMap<>();

    Revision current() { return current; }
    void publish(Revision revision) { current = revision; }

    synchronized Runnable retain(Revision revision) {
        String hash = revision.artifact().sha1();
        active.compute(hash, (key, held) -> held == null ? new Held(revision, 1) : new Held(held.revision(), held.requests() + 1));
        return () -> release(hash);
    }

    private synchronized void release(String hash) {
        active.computeIfPresent(hash, (key, held) -> held.requests() == 1 ? null : new Held(held.revision(), held.requests() - 1));
    }

    synchronized Revision resolve(String hash) {
        Revision latest = current;
        if (hash == null || latest == null || latest.artifact().sha1().equals(hash)) return latest;
        Held held = active.get(hash);
        return held == null ? null : held.revision();
    }
}
