package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.ConfigStore;
import br.com.cobbleverse.generations.data.GymStructures;
import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.Identifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Developer-only fixture; loaded only with modified metadata on an isolated server. */
public final class GymIntegrationVerification implements ModInitializer {
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            var result=new LinkedHashMap<String,Object>();
            var failed=new ArrayList<String>();
            int gyms=0, other=0;
            try {
                var rules=GenerationControl.rules();
                for(String zipName:List.of("COBBLEVERSE-DP-v31.zip","COBBLEVERSE-Johto-DP.zip","COBBLEVERSE-Hoenn-DP.zip","COBBLEVERSE-Sinnoh-DP.zip")) {
                    try(var zip=new ZipFile(Path.of("datapacks",zipName).toFile())) {
                        var entries=zip.entries();
                        while(entries.hasMoreElements()) {
                            var entry=entries.nextElement(); var name=entry.getName();
                            String prefix="data/cobbleverse/worldgen/structure/";
                            if(!name.startsWith(prefix)||!name.endsWith(".json")) continue;
                            String path="worldgen/structure/"+name.substring(prefix.length());
                            var id=Identifier.of("cobbleverse",path);
                            JsonObject original;
                            try(var reader=new InputStreamReader(zip.getInputStream(entry),StandardCharsets.UTF_8)) {original=JsonParser.parseReader(reader).getAsJsonObject();}
                            var resource=server.getResourceManager().getResource(id).orElseThrow();
                            JsonObject loaded; try(var reader=resource.getReader()){loaded=JsonParser.parseReader(reader).getAsJsonObject();}
                            var region=GymStructures.region(path);
                            JsonElement expected=original.get("biomes");
                            if(region!=null) {gyms++; if(rules.filterGyms()&&!rules.regionAllowed(region)) expected=new JsonArray();}
                            else other++;
                            if(!Objects.equals(expected,loaded.get("biomes"))) failed.add(id+" expected="+expected+" loaded="+loaded.get("biomes"));
                        }
                    }
                }
                result.put("gymsChecked",gyms);result.put("otherStructuresChecked",other);result.put("failures",failed);
                result.put("success",gyms==36 && other>=30 && failed.isEmpty());
            } catch(Throwable e) {result.put("fatal",e.toString());e.printStackTrace();}
            try {ConfigStore.write(Path.of("gym-integration-result.json").toAbsolutePath(),result);}
            catch(Exception e){e.printStackTrace();}
            server.stop(false);
        });
    }
}
