package io.github.lino9999.blocksadder.itembehavior;

import io.github.lino9999.blocksadder.api.event.CustomContentBreakEvent;
import io.github.lino9999.blocksadder.api.event.CustomContentInteractEvent;

public interface ContentActionHook {
    interface BreakAttempt {
        boolean allowed();
        void committed();
    }
    BreakAttempt beforeBreak(CustomContentBreakEvent event);
    default boolean beforeUse(CustomContentInteractEvent event) { return true; }
}
