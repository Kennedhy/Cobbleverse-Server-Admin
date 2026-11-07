package br.com.cobbleverse.generations.integration;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;

/** Captures both present and absent offline store paths, without touching the files. */
final class OfflineFileLedger {
    private record Stamp(boolean exists,long size,FileTime modified,Object key) {}
    private final Map<Path,Stamp> stamps=new ConcurrentHashMap<>();
    private static Stamp stamp(Path path) throws IOException {
        try {
            BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class);
            return new Stamp(true,a.size(),a.lastModifiedTime(),a.fileKey());
        } catch(NoSuchFileException absent) { return new Stamp(false,0,null,null); }
    }
    void read(Path path,ReadOnlyStores.Loader<Boolean> loader) throws Exception {
        Path backup=Path.of(path.toString()+".old");
        Stamp before=stamp(path), backupBefore=stamp(backup);
        ReadOnlyStores.read(path,loader);
        Stamp after=stamp(path), backupAfter=stamp(backup);
        if(!before.equals(after) || !backupBefore.equals(backupAfter)) throw new IllegalStateException("Arquivo alterado durante a leitura: "+path);
        record(path,after); record(backup,backupAfter);
    }
    private void record(Path path,Stamp stamp) {
        Stamp old=stamps.putIfAbsent(path,stamp);
        if(old!=null && !old.equals(stamp)) throw new IllegalStateException("Arquivo alterado durante a análise: "+path);
    }
    CompletableFuture<Void> verify() {
        Map<Path,Stamp> snapshot=Map.copyOf(stamps);
        return CompletableFuture.runAsync(()-> {
            for(var entry:snapshot.entrySet()) try {
                if(!entry.getValue().equals(stamp(entry.getKey()))) throw new IllegalStateException("Arquivo de Pokémon mudou durante a análise: "+entry.getKey());
            } catch(IOException e) { throw new CompletionException(e); }
        });
    }
}
