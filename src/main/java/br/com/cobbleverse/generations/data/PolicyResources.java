package br.com.cobbleverse.generations.data;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.google.gson.*;
import net.minecraft.resource.*;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A highest-priority, in-memory datapack. Original ZIPs are never edited. */
public final class PolicyResources extends AbstractFileResourcePack {
    private final Map<Identifier, byte[]> files;
    public static volatile int transformedFiles;
    public static volatile int transformedTeams;
    // Keep raw trainer data compact while the server is idle. The web validator parses one
    // trainer at a time only when an administrator explicitly requests a preview.
    private record Snapshot(SpeciesCatalog catalog,Map<Identifier,String> trainers,Map<String,String> series) {}
    private static volatile Snapshot snapshot;

    public record SpeciesSearchEntry(String id, String name, int dex, int generation) {}

    /** Small, on-demand projection for the authenticated web search; the full catalog stays server-side. */
    public static List<SpeciesSearchEntry> searchSpecies(String query, int limit) {
        Snapshot current = snapshot;
        if (current == null) return null;
        String needle = query.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_:]", "");
        if (needle.length() < 2) return List.of();
        int bounded = Math.max(1, Math.min(50, limit));
        return current.catalog.entries().stream()
            .filter(entry -> entry.id().toLowerCase(Locale.ROOT).contains(needle))
            .sorted(Comparator.comparingInt(SpeciesCatalog.Entry::dex).thenComparing(SpeciesCatalog.Entry::id))
            .limit(bounded)
            .map(entry -> new SpeciesSearchEntry(entry.id(), displayName(entry.id()), entry.dex(), GenerationRules.generation(entry.dex())))
            .toList();
    }

    private static String displayName(String id) {
        String slug = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        StringBuilder name = new StringBuilder(slug.length());
        boolean wordStart = true;
        for (char character : slug.toCharArray()) {
            name.append(wordStart ? Character.toUpperCase(character) : character);
            wordStart = Character.isWhitespace(character);
        }
        return name.toString();
    }

    private PolicyResources(Map<Identifier, byte[]> files) {
        super(new ResourcePackInfo("generation_control:policy", Text.literal("Regras de gerações do servidor"), ResourcePackSource.BUILTIN, Optional.empty()));
        this.files = Map.copyOf(files);
    }

    public static List<ResourcePack> augment(ResourceType type, List<ResourcePack> input) {
        if (type != ResourceType.SERVER_DATA || !GenerationControl.ready()) return input;
        try { return generate(type, input); }
        catch (Exception failure) {
            if (GenerationControl.rejectStartupPolicy(failure)) throw new IllegalStateException("Revisão pendente rejeitada. A ativa foi preservada; reinicie para carregar novamente os seus datapacks, sem aplicação parcial.",failure);
            throw failure;
        }
    }

    private static List<ResourcePack> generate(ResourceType type, List<ResourcePack> input) {
        List<ResourcePack> originals = input.stream().filter(p -> !p.getId().equals("generation_control:policy")).toList();
        Map<Identifier, Source> source = new TreeMap<>();
        for (ResourcePack pack : originals) for (String namespace : pack.getNamespaces(type)) {
            for (String prefix : List.of("species", "species_additions", "spawn_pool_world", "trainers", "series", "worldgen/structure", "function", "raid/boss")) {
                pack.findResources(type, namespace, prefix, (id, supplier) -> source.put(id, new Source(pack.getId(), supplier)));
            }
        }
        SpeciesCatalog catalog = new SpeciesCatalog();
        Map<String,JsonObject> definitions=new TreeMap<>();
        for (var entry : source.entrySet()) if (entry.getKey().getPath().startsWith("species/") && entry.getKey().getPath().endsWith(".json")) {
            JsonObject json = json(entry.getValue());
            if (json != null && json.has("name")) definitions.put(SpeciesCatalog.id(entry.getKey().getNamespace(),json),json);
        }
        // Cobblemon appends collection additions, merges maps and replaces scalar/object properties.
        for(var entry:source.entrySet()) if(entry.getKey().getPath().startsWith("species_additions/") && entry.getKey().getPath().endsWith(".json")) {
            var addition=json(entry.getValue());
            if(addition==null || !addition.has("target")) continue;
            var target=definitions.get(GenerationRules.speciesKey(addition.get("target").getAsString()));
            if(target==null) continue;
            for(String field:List.of("implemented","forms","baseStats","primaryType","secondaryType","abilities","moves")) {
                if(!addition.has(field)) continue;
                var value=addition.get(field);
                if(field.equals("forms") && target.has("forms")) for(var form:value.getAsJsonArray()) target.getAsJsonArray("forms").add(form.deepCopy());
                else if(field.equals("baseStats") && target.has("baseStats")) value.getAsJsonObject().entrySet().forEach(stat -> target.getAsJsonObject(field).add(stat.getKey(),stat.getValue().deepCopy()));
                else target.add(field,value.deepCopy());
            }
        }
        for(var entry:definitions.entrySet()) catalog.add(entry.getKey().substring(0,entry.getKey().indexOf(':')),entry.getValue());
        GenerationRules rules = GenerationControl.rules();
        Map<Identifier, byte[]> output = new TreeMap<>();
        Map<Identifier,String> rawTrainers=new TreeMap<>();
        Map<String,String> rawSeries=new TreeMap<>();
        if(rules.filterGyms()) for(String region:rules.config().regions) {
            if(!region.equals("kanto") && originals.stream().noneMatch(pack -> pack.getId().toLowerCase(Locale.ROOT).contains(region)))
                throw new IllegalStateException("Datapack regional não carregado: "+region);
        }
        int teams = 0;
        for (var entry : source.entrySet()) {
            Identifier id = entry.getKey();
            String path = id.getPath();
            if (path.startsWith("function/") && path.endsWith(".mcfunction")) {
                String original = text(entry.getValue());
                String filtered = original.lines().filter(line -> {
                    if (!rules.filterGyms()) return true;
                    if (!line.stripLeading().startsWith("datapack enable")) return true;
                    String lower = line.toLowerCase(Locale.ROOT);
                    return br.com.cobbleverse.generations.config.GenerationConfig.REGIONS.stream().noneMatch(r -> lower.contains(r) && !rules.regionAllowed(r));
                }).reduce("", (a,b) -> a+b+"\n");
                if (!filtered.equals(original)) output.put(id, filtered.getBytes(StandardCharsets.UTF_8));
                continue;
            }
            if (!path.endsWith(".json") || path.startsWith("species")) continue;
            JsonObject json = json(entry.getValue());
            if (json == null) continue;
            boolean changed = false;
            if (path.startsWith("spawn_pool_world/") && json.has("spawns")) {
                JsonArray filtered = new JsonArray();
                for (JsonElement spawn : json.getAsJsonArray("spawns")) {
                    JsonObject detail = spawn.getAsJsonObject();
                    if (!detail.has("pokemon") || catalog.allowed(rules, detail.get("pokemon").getAsString())) filtered.add(detail);
                }
                changed = filtered.size() != json.getAsJsonArray("spawns").size();
                json.add("spawns", filtered);
            } else if (id.getNamespace().equals("cobblemonraiddens") && path.startsWith("raid/boss/") && json.has("pokemon")) {
                JsonObject pokemon = json.getAsJsonObject("pokemon");
                String properties = pokemon.get("species").getAsString();
                if (pokemon.has("form")) properties += " form=" + pokemon.get("form").getAsString();
                if (!catalog.allowed(rules, properties)) { json.addProperty("weight", 0); changed = true; }
            } else if (id.getNamespace().equals("rctmod") && path.startsWith("trainers/") && json.has("team")) {
                rawTrainers.put(id,json.toString());
                changed = rules.filterTeams() && adaptTeam(id.toString(),json, catalog, rules);
                if (changed) teams++;
            } else if(id.getNamespace().equals("rctmod") && path.startsWith("series/")) {
                String region=path.substring(path.lastIndexOf('/')+1).replace(".json","");
                rawSeries.put(region,json.toString());
                changed=SeriesPolicy.apply(region,json,rules);
            } else if (rules.filterGyms() && id.getNamespace().equals("cobbleverse") && path.startsWith("worldgen/structure/")) {
                String region = GymStructures.region(path);
                if (region != null && !rules.regionAllowed(region)) { json.add("biomes", new JsonArray()); changed = true; }
            }
            if (changed) output.put(id, json.toString().getBytes(StandardCharsets.UTF_8));
        }
        transformedFiles = output.size();
        transformedTeams = teams;
        snapshot=new Snapshot(catalog,Map.copyOf(rawTrainers),Map.copyOf(rawSeries));
        GenerationControl.LOGGER.info("Revisão {}: {} arquivos sobrepostos; {} equipes adaptadas.", rules.config().revision, output.size(), teams);
        List<ResourcePack> result = new ArrayList<>(originals);
        result.add(new PolicyResources(output));
        return result;
    }

    public static void previewTeams(GenerationRules rules,br.com.cobbleverse.generations.integration.ValidationReport report) {
        TeamPreview preview=new TeamPreview(rules,report);
        while(!preview.step(Integer.MAX_VALUE,Long.MAX_VALUE)) { /* synchronous verification-only caller */ }
    }
    public static TeamPreview beginTeamPreview(GenerationRules rules,br.com.cobbleverse.generations.integration.ValidationReport report) {
        return new TeamPreview(rules,report);
    }
    public static final class TeamPreview {
        private final Snapshot current; private final GenerationRules rules;
        private final br.com.cobbleverse.generations.integration.ValidationReport report;
        private final Iterator<Map.Entry<Identifier,String>> entries;
        private TeamPreview(GenerationRules rules,br.com.cobbleverse.generations.integration.ValidationReport report) {
            this.rules=rules; this.report=report; current=snapshot;
            if(current==null) { report.errors.add("Catálogo de equipes ainda não preparado. Aguarde o carregamento do servidor."); entries=Collections.emptyIterator(); return; }
            report.trainerTeams=current.trainers.size();
            if(rules.filterGyms()) for(String region:rules.config().regions) {
                var metadata=current.series.get(region);
                if(metadata==null) report.errors.add("Circuito sem metadados RCT: "+region);
                else if(SeriesPolicy.apply(region,JsonParser.parseString(metadata).getAsJsonObject(),rules)) report.warnings.add("Pré-requisitos de "+region+" ajustados para os circuitos selecionados; o circuito inicial pode começar sem regiões excluídas.");
            }
            if(!rules.filterTeams()) { report.warnings.add("Equipes de NPCs não serão filtradas; podem usar espécies de outras gerações."); entries=Collections.emptyIterator(); }
            else entries=current.trainers.entrySet().iterator();
        }
        public void ensureCurrent() {
            if(current!=null && snapshot!=current) throw new IllegalStateException("Os dados de treinadores mudaram durante a prévia; valide novamente.");
        }
        public boolean step(int maximum,long deadline) {
            ensureCurrent();
            int examined=0;
            while(entries.hasNext() && examined++<maximum && System.nanoTime()<deadline) {
                var entry=entries.next();
                try { if(adaptTeam(entry.getKey().toString(),JsonParser.parseString(entry.getValue()).getAsJsonObject(),current.catalog,rules)) report.adaptedTeams++; }
                catch(RuntimeException e) { if(report.errors.size()<40) report.errors.add("Equipe sem solução: "+entry.getKey()+": "+e.getMessage()); }
            }
            return !entries.hasNext();
        }
    }

    private static String properties(JsonObject pokemon) {
        String raw=pokemon.get("species").getAsString()+(pokemon.has("form") ? " form="+pokemon.get("form").getAsString() : "");
        if(pokemon.has("aspects")) for(var aspect:pokemon.getAsJsonArray("aspects")) raw+=" "+aspect.getAsString();
        return raw;
    }

    private static boolean adaptTeam(String trainerId,JsonObject trainer, SpeciesCatalog catalog, GenerationRules rules) {
        boolean changed = false;
        Set<String> used = new HashSet<>();
        int slot=0;
        for (JsonElement element : trainer.getAsJsonArray("team")) {
            JsonObject pokemon = element.getAsJsonObject();
            String original = pokemon.get("species").getAsString();
            int pokemonLevel=pokemon.has("level") ? pokemon.get("level").getAsInt() : 50;
            var preferred=KantoTeams.preferred(trainerId,slot++,catalog,rules);
            boolean replace=!catalog.allowed(rules,properties(pokemon)) || (preferred!=null && !preferred.id().equals(GenerationRules.speciesKey(original)));
            if (replace) {
                SpeciesCatalog.Entry replacement = preferred!=null ? preferred : catalog.replacement(rules, catalog.get(original), used,pokemonLevel);
                pokemon.addProperty("species", replacement.id().replace("cobblemon:", ""));
                pokemon.remove("form");
                pokemon.remove("aspects");
                pokemon.remove("gender");
                pokemon.addProperty("ability", replacement.ability());
                JsonArray moves = new JsonArray();
                if (pokemon.has("moveset")) for (JsonElement move : pokemon.getAsJsonArray("moveset")) if (replacement.moves().contains(move.getAsString())) moves.add(move);
                int level = pokemon.has("level") ? pokemon.get("level").getAsInt() : 50;
                List<String> learned = new ArrayList<>();
                for (String raw : replacement.levelMoves()) {
                    int separator = raw.indexOf(':');
                    if (Integer.parseInt(raw.substring(0,separator)) <= level) learned.add(raw.substring(separator+1));
                }
                Collections.reverse(learned);
                for (String move : learned) {
                    if (moves.size() >= 4) break;
                    JsonPrimitive value = new JsonPrimitive(move);
                    if (!moves.contains(value)) moves.add(value);
                }
                if (moves.isEmpty()) throw new IllegalStateException("Equipe sem golpes válidos para " + replacement.id());
                pokemon.add("moveset", moves);
                used.add(replacement.id());
                changed = true;
            } else used.add(GenerationRules.speciesKey(original));
            // A legal species may still have pack moves/abilities that belong to a different form.
            var resolved=catalog.resolve(properties(pokemon));
            var effective=resolved==null ? null : resolved.entry();
            if(effective==null) throw new IllegalStateException("Espécie sem dados: "+pokemon.get("species"));
            if(pokemon.has("ability") && !effective.abilities().contains(pokemon.get("ability").getAsString())) {
                pokemon.addProperty("ability",effective.ability()); changed=true;
            }
            if(pokemon.has("moveset")) {
                JsonArray legal=new JsonArray();
                for(var move:pokemon.getAsJsonArray("moveset")) if(effective.moves().contains(move.getAsString()) && !legal.contains(move)) legal.add(move);
                int level=pokemon.has("level") ? pokemon.get("level").getAsInt() : 50;
                var learned=new ArrayList<>(effective.levelMoves()); Collections.reverse(learned);
                for(String raw:learned) {
                    int split=raw.indexOf(':');
                    if(legal.size()<4 && Integer.parseInt(raw.substring(0,split))<=level) {
                        var move=new JsonPrimitive(raw.substring(split+1)); if(!legal.contains(move)) legal.add(move);
                    }
                }
                if(legal.isEmpty()) throw new IllegalStateException("Nenhum golpe válido para "+effective.id()+" no nível "+level);
                if(!legal.equals(pokemon.getAsJsonArray("moveset"))) { pokemon.add("moveset",legal); changed=true; }
            }
            if (!rules.allowMegas() && pokemon.has("heldItem")) {
                String items = pokemon.get("heldItem").toString().toLowerCase(Locale.ROOT);
                if (items.contains("mega") || items.contains("red_orb") || items.contains("blue_orb") || items.contains("redorb") || items.contains("blueorb")) {
                    pokemon.remove("heldItem"); changed = true;
                }
            }
        }
        return changed;
    }

    private record Source(String packId, InputSupplier<InputStream> input) {}
    private static String text(Source source) {
        try (InputStream stream = source.input.get()) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        catch (IOException e) { throw new IllegalStateException("Falha ao ler dados de " + source.packId, e); }
    }
    private static JsonObject json(Source source) {
        try { JsonElement parsed = JsonParser.parseString(text(source)); return parsed.isJsonObject() ? parsed.getAsJsonObject() : null; }
        catch (JsonParseException e) { throw new IllegalStateException("JSON inválido em " + source.packId, e); }
    }

    @Override public InputSupplier<InputStream> openRoot(String... segments) {
        if (segments.length == 1 && segments[0].equals("pack.mcmeta")) return () -> new ByteArrayInputStream("{\"pack\":{\"pack_format\":48,\"description\":\"Generation Control\"}}".getBytes(StandardCharsets.UTF_8));
        return null;
    }
    @Override public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        byte[] bytes = type == ResourceType.SERVER_DATA ? files.get(id) : null;
        return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
    }
    @Override public void findResources(ResourceType type, String namespace, String prefix, ResultConsumer consumer) {
        if (type != ResourceType.SERVER_DATA) return;
        files.forEach((id, bytes) -> { if (id.getNamespace().equals(namespace) && id.getPath().startsWith(prefix)) consumer.accept(id, () -> new ByteArrayInputStream(bytes)); });
    }
    @Override public Set<String> getNamespaces(ResourceType type) {
        Set<String> result = new HashSet<>();
        if (type == ResourceType.SERVER_DATA) files.keySet().forEach(id -> result.add(id.getNamespace()));
        return result;
    }
    @Override public void close() {}
}
