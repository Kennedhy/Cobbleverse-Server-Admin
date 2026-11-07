package br.com.cobbleverse.generations.integration;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/** Reproduces Cobblemon's one-file-per-store naming without creating directories. */
public final class StorePaths {
    private StorePaths() {}
    public static Path path(String root,boolean nested,boolean perClass,String extension,String className,UUID id) {
        String type=className.toLowerCase(Locale.ROOT);
        Path parent=Path.of(root);
        if(perClass) parent=parent.resolve(type);
        if(nested) parent=parent.resolve(id.toString().substring(0,2));
        return parent.resolve(id+(perClass ? "" : "-"+type)+"."+extension);
    }
}
