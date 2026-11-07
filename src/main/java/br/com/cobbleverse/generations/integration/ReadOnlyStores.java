package br.com.cobbleverse.generations.integration;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a specific store without invoking a provider's file-repair operations. */
public final class ReadOnlyStores {
    @FunctionalInterface public interface Loader<T> { T load(File file) throws Exception; }
    private ReadOnlyStores() {}
    public static <T> T read(Path path,Loader<T> loader) throws Exception {
        Path old=Path.of(path.toString()+".old");
        if(Files.notExists(path)) {
            if(!Files.notExists(old)) throw new IllegalStateException("Arquivo principal ausente ou inacessível; backup preservado: "+path);
            return null;
        }
        if(!Files.isRegularFile(path) || !Files.isReadable(path) || Files.size(path)==0)
            throw new IllegalStateException("Arquivo de Pokémon vazio ou inacessível, preservado: "+path);
        T result=loader.load(path.toFile());
        if(result==null) throw new IllegalStateException("Arquivo de Pokémon inválido, preservado: "+path);
        return result;
    }
}
