package br.com.cobbleverse.generations.verification;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.data.GymStructures;
import br.com.cobbleverse.generations.data.PolicyResources;
import br.com.cobbleverse.generations.integration.CobblemonIntegration;
import br.com.cobbleverse.generations.integration.ValidationReport;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Focused disposable-world check for the resources loaded by the active generation policy. */
public final class GenerationPolicyVerification {
    private GenerationPolicyVerification() {}

    public static Map<String, Object> run(MinecraftServer server) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> failures = new java.util.ArrayList<>();
        try {
            GenerationRules rules = GenerationControl.rules();
            var config = rules.config();
            boolean kantoOnly = config.mode.equals("single") && config.selectedGeneration == 1
                    && config.automaticRegions && config.generations.equals(java.util.Set.of(1))
                    && config.regions.equals(java.util.Set.of("kanto")) && rules.filterGyms();
            if (!kantoOnly) failures.add("The fixture did not load automatic Kanto-only rules.");
            result.put("mode", config.mode);
            result.put("generations", config.generations);
            result.put("regions", config.regions);
            result.put("filterGyms", rules.filterGyms());

            int spawnFiles = 0;
            int spawnEntries = 0;
            for (var entry : server.getResourceManager().findResources("spawn_pool_world",
                    id -> id.getPath().endsWith(".json")).entrySet()) {
                try (var reader = entry.getValue().getReader()) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (!root.has("spawns")) continue;
                    spawnFiles++;
                    for (JsonElement spawn : root.getAsJsonArray("spawns")) {
                        JsonObject detail = spawn.getAsJsonObject();
                        if (!detail.has("pokemon")) continue;
                        spawnEntries++;
                        String raw = detail.get("pokemon").getAsString();
                        if (!CobblemonIntegration.allowed(PokemonProperties.Companion.parse(raw)))
                            failures.add("Spawn fora da política carregado: " + entry.getKey() + " -> " + raw);
                    }
                }
            }
            result.put("spawnFilesInspected", spawnFiles);
            result.put("spawnEntriesInspected", spawnEntries);
            if (spawnFiles == 0 || spawnEntries == 0) failures.add("Nenhum spawn de Pokémon foi encontrado para inspecionar.");

            int gymStructuresInspected = 0;
            int activeGymStructures = 0;
            Map<String, Integer> activeByRegion = new TreeMap<>();
            for (var entry : server.getResourceManager().findResources("worldgen/structure",
                    id -> id.getNamespace().equals("cobbleverse") && id.getPath().endsWith(".json")).entrySet()) {
                String region = GymStructures.region(entry.getKey().getPath());
                if (region == null) continue;
                gymStructuresInspected++;
                JsonObject structure;
                try (var reader = entry.getValue().getReader()) {
                    structure = JsonParser.parseReader(reader).getAsJsonObject();
                }
                boolean hasBiomeTargets = hasBiomeTargets(structure.get("biomes"));
                if (hasBiomeTargets) {
                    activeGymStructures++;
                    activeByRegion.merge(region, 1, Integer::sum);
                }
                if (rules.regionAllowed(region) != hasBiomeTargets)
                    failures.add("Estrutura de ginásio incompatível com a política: " + entry.getKey()
                            + " (região=" + region + ", biomasAtivos=" + hasBiomeTargets + ")");
            }
            for (String region : GymStructures.regions()) {
                int expectedActive = rules.regionAllowed(region) ? GymStructures.count(region) : 0;
                int actualActive = activeByRegion.getOrDefault(region, 0);
                if (actualActive != expectedActive)
                    failures.add("Circuito " + region + ": esperava " + expectedActive
                            + " estruturas ativas, encontrou " + actualActive + ".");
            }
            result.put("gymStructuresInspected", gymStructuresInspected);
            result.put("activeGymStructures", activeGymStructures);
            result.put("activeGymStructuresByRegion", activeByRegion);
            if (gymStructuresInspected == 0) failures.add("Nenhuma estrutura de ginásio do pack foi encontrada.");

            ValidationReport teamReport = new ValidationReport();
            PolicyResources.previewTeams(rules, teamReport);
            result.put("trainerTeamsInspected", teamReport.trainerTeams);
            result.put("trainerTeamsAdapted", teamReport.adaptedTeams);
            if (teamReport.trainerTeams == 0) failures.add("Nenhuma equipe RCT foi encontrada para prévia.");
            failures.addAll(teamReport.errors);
        } catch (Throwable failure) {
            failures.add("Verificação interrompida: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            failure.printStackTrace();
        }
        result.put("failures", failures);
        result.put("success", failures.isEmpty());
        return result;
    }

    private static boolean hasBiomeTargets(JsonElement biomes) {
        if (biomes == null || biomes.isJsonNull()) return false;
        if (biomes.isJsonArray()) return !biomes.getAsJsonArray().isEmpty();
        if (biomes.isJsonObject()) return !biomes.getAsJsonObject().entrySet().isEmpty();
        if (biomes.isJsonPrimitive()) return !biomes.getAsString().isBlank();
        return true;
    }
}
