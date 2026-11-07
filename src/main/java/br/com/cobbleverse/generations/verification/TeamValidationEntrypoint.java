package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.config.*;
import br.com.cobbleverse.generations.data.*;
import br.com.cobbleverse.generations.integration.ValidationService;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.util.Identifier;

/** Runs only through test metadata on a disposable server. */
public final class TeamValidationEntrypoint implements ModInitializer {
    private long tickStart,longest; private boolean measure; private int measured;
    public void onInitialize() {
        ServerTickEvents.START_SERVER_TICK.register(server->tickStart=System.nanoTime());
        ValidationService.register();
        ServerTickEvents.END_SERVER_TICK.register(new ServerTickEvents.EndTick() {
            boolean started; int ticks;
            public void onEndTick(net.minecraft.server.MinecraftServer server) {
                if(measure) { longest=Math.max(longest,System.nanoTime()-tickStart); measured++; }
                if(++ticks>1000) { finish(server,Map.of("fatal","timeout")); return; }
                if(started) return; started=true;
                try {
                    var catalog=new SpeciesCatalog();
                    catalog.add("cobblemon",JsonParser.parseString("{\"name\":\"pikachu\",\"nationalPokedexNumber\":25,\"primaryType\":\"electric\",\"abilities\":[\"static\"],\"moves\":[\"1:tackle\"]}").getAsJsonObject());
                    catalog.add("cobblemon",JsonParser.parseString("{\"name\":\"pichu\",\"nationalPokedexNumber\":172,\"primaryType\":\"electric\",\"abilities\":[\"static\"],\"moves\":[\"1:tackle\"]}").getAsJsonObject());
                    Map<Identifier,String> trainers=new LinkedHashMap<>();
                    for(int i=0;i<1600;i++) trainers.put(Identifier.of("test","trainers/trainer_"+i),"{\"team\":[{\"species\":\"pichu\",\"level\":5,\"moveset\":[\"tackle\"]}]}");
                    Class<?> type=Class.forName("br.com.cobbleverse.generations.data.PolicyResources$Snapshot");
                    var constructor=type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
                    var field=PolicyResources.class.getDeclaredField("snapshot"); field.setAccessible(true); field.set(null,constructor.newInstance(catalog,trainers,Map.of()));
                    var active=new GenerationConfig(); active.selectedGeneration=2; active.filterGyms=false;
                    Path base=Path.of("team-policy-fixture").toAbsolutePath(); ConfigStore.write(base.resolve("generation-control/active.json"),active);
                    GenerationControl.openWorld(base); var candidate=active.copy(); candidate.selectedGeneration=1;
                    long before=System.nanoTime(); long[] startMs={0};
                    boolean accepted=ValidationService.start(server,candidate,report->{
                        Map<String,Object> result=new LinkedHashMap<>(); result.put("errors",report.errors);
                        result.put("teams",report.trainerTeams); result.put("adapted",report.adaptedTeams);
                        result.put("allTeamsValidated",report.errors.isEmpty() && report.trainerTeams==1600 && report.adaptedTeams==1600);
                        result.put("spreadAcrossTicks",measured>=190); result.put("measuredTicks",measured);
                        result.put("startMs",startMs[0]); result.put("maxServerTickMs",Math.max(longest,System.nanoTime()-tickStart)/1_000_000.0);
                        finish(server,result);
                    });
                    startMs[0]=(System.nanoTime()-before)/1_000_000;
                    if(!accepted) { finish(server,Map.of("fatal","job rejected")); return; }
                    measure=true;
                } catch(Throwable e) { e.printStackTrace(); finish(server,Map.of("fatal",e.toString())); }
            }
            void finish(net.minecraft.server.MinecraftServer server,Map<String,Object> result) {
                try { ConfigStore.write(Path.of("team-validation-result.json").toAbsolutePath(),result); } catch(Exception e) { e.printStackTrace(); }
                server.stop(false);
            }
        });
    }
}
