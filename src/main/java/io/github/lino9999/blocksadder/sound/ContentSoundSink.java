package io.github.lino9999.blocksadder.sound;

import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface ContentSoundSink {
    ContentSoundSink NONE = new ContentSoundSink() {
        public void placed(Location location, String id, Player player) {}
        public void broken(Location location, String id, Player player) {}
        public void hit(Location location, String id, Player player) {}
    };

    void placed(Location location, String id, Player player);
    void broken(Location location, String id, Player player);
    void hit(Location location, String id, Player player);
}
