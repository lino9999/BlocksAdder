package io.github.lino9999.blocksadder.pack;

import java.nio.file.Path;

public record PackArtifact(Path path, String sha1, String sha256, long bytes, int entries) {}
