package io.github.lino9999.blocksadder.environment;

record EnvironmentCell(EnvironmentPosition position, String id, EnvironmentKind kind, int level, boolean source) {
    EnvironmentCell level(int value) {
        return new EnvironmentCell(position, id, kind, value, source);
    }
}
