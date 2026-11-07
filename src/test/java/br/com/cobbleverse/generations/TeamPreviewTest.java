package br.com.cobbleverse.generations;

import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.data.*;
import br.com.cobbleverse.generations.integration.ValidationReport;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.google.gson.*;
import net.minecraft.util.Identifier;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TeamPreviewTest {
    @Test void incrementalPreviewMatchesSynchronousAndRejectsChangedCatalog() throws Exception {
        Field snapshot=PolicyResources.class.getDeclaredField("snapshot"); snapshot.setAccessible(true);
        Object previous=snapshot.get(null);
        try {
            Class<?> type=Class.forName("br.com.cobbleverse.generations.data.PolicyResources$Snapshot");
            Constructor<?> constructor=type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            var catalog=new SpeciesCatalog();
            catalog.add("cobblemon",JsonParser.parseString("{\"name\":\"pikachu\",\"nationalPokedexNumber\":25,\"primaryType\":\"electric\",\"abilities\":[\"static\"],\"moves\":[\"1:tackle\"]}").getAsJsonObject());
            catalog.add("cobblemon",JsonParser.parseString("{\"name\":\"pichu\",\"nationalPokedexNumber\":172,\"primaryType\":\"electric\",\"abilities\":[\"static\"],\"moves\":[\"1:tackle\"]}").getAsJsonObject());
            Map<Identifier,String> teams=new LinkedHashMap<>();
            for(int i=0;i<19;i++) teams.put(Identifier.of("test","trainers/trainer_"+i),"{\"team\":[{\"species\":\"pichu\",\"level\":5,\"moveset\":[\"tackle\"]}]}");
            Object original=constructor.newInstance(catalog,teams,Map.of()); snapshot.set(null,original);
            var config=new GenerationConfig(); config.filterGyms=false; var rules=new GenerationRules(config);
            var expected=new ValidationReport(); PolicyResources.previewTeams(rules,expected);
            var actual=new ValidationReport(); var job=PolicyResources.beginTeamPreview(rules,actual);
            assertFalse(job.step(8,Long.MAX_VALUE)); assertEquals(8,actual.adaptedTeams);
            assertFalse(job.step(8,Long.MAX_VALUE)); assertTrue(job.step(8,Long.MAX_VALUE));
            assertEquals(19,actual.trainerTeams); assertEquals(expected.adaptedTeams,actual.adaptedTeams);
            assertEquals(expected.errors,actual.errors); assertEquals(19,actual.adaptedTeams);
            assertTrue(teams.values().iterator().next().contains("\"species\":\"pichu\""));
            var changed=PolicyResources.beginTeamPreview(rules,new ValidationReport());
            assertFalse(changed.step(1,Long.MAX_VALUE)); snapshot.set(null,constructor.newInstance(catalog,teams,Map.of()));
            assertThrows(IllegalStateException.class,()->changed.step(8,Long.MAX_VALUE));
            assertThrows(IllegalStateException.class,job::ensureCurrent);
        } finally { snapshot.set(null,previous); }
    }
}
