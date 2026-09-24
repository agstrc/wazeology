package com.wazeology.core;

import java.io.File;
import java.io.IOException;

/** The baked build assets read from a directory (build/assets on the host). */
public final class FileAssets implements BuildPipeline.Assets {

    private final File root;

    public FileAssets(File root) {
        this.root = root;
    }

    @Override
    public byte[] read(String name) throws IOException {
        return BundleInput.readFile(new File(root, name));
    }
}
