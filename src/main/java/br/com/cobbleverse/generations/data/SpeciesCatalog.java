package br.com.cobbleverse.generations.data;

import br.com.cobbleverse.generations.rules.GenerationRules;
import br.com.cobbleverse.generations.rules.FormNames;
import com.google.gson.*;
import java.util.*;

/** Catalog built from the effective datapacks before the runtime species registry loads. */
public final class SpeciesCatalog {
    public record Entry(String id, int dex, Set<String> types, int strength, Set<String> moves, List<String> levelMoves, String ability, Set<String> abilities) {}
    private final Map<String, Entry> entries = new TreeMap<>();
    private record Variant(String name,Set<String> aspects,Entry entry) {}
    public record Resolved(Entry entry,String form,Set<String> aspects) {}
    private final Map<String,List<Variant>> variants=new TreeMap<>();
    public static String id(String namespace,JsonObject json) {
        return namespace+":"+json.get("name").getAsString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]","");
    }

    public void add(String namespace, JsonObject json) {
        if (!json.has("name") || !json.has("nationalPokedexNumber")) return;
        if(json.has("implemented") && !json.get("implemented").getAsBoolean()) return;
        String id = id(namespace,json);
        Set<String> types = new TreeSet<>();
        for (String key : List.of("primaryType", "secondaryType")) if (json.has(key) && !json.get(key).isJsonNull()) types.add(json.get(key).getAsString());
        int strength = 0;
        if (json.has("baseStats")) for (JsonElement value : json.getAsJsonObject("baseStats").asMap().values()) strength += value.getAsInt();
        Set<String> moves = new LinkedHashSet<>();
        List<String> levelMoves = new ArrayList<>();
        if (json.has("moves")) for (JsonElement value : json.getAsJsonArray("moves")) {
            String raw = value.getAsString();
            int separator = raw.indexOf(':');
            if (separator < 0) continue;
            String category=raw.substring(0,separator);
            boolean levelMove=category.matches("[0-9]+");
            // Cobblemon's getAllLegalMoves excludes legacy and special moves.
            if(!levelMove && !Set.of("tm","egg","tutor","form_change").contains(category)) continue;
            String move = raw.substring(separator + 1);
            moves.add(move);
            if (levelMove) levelMoves.add(raw);
        }
        String ability = "";
        Set<String> abilities = new HashSet<>();
        if (json.has("abilities")) for (JsonElement value : json.getAsJsonArray("abilities")) {
            String name=value.getAsString().replaceFirst("^h:","");
            abilities.add(name);
            if (ability.isEmpty() && !value.getAsString().startsWith("h:")) ability=name;
        }
        if(ability.isEmpty() && !abilities.isEmpty()) ability=abilities.iterator().next();
        entries.put(id, new Entry(id, json.get("nationalPokedexNumber").getAsInt(), Set.copyOf(types), strength, Set.copyOf(moves), List.copyOf(levelMoves), ability,Set.copyOf(abilities)));
        List<Variant> forms=new ArrayList<>();
        if(json.has("forms")) for(var rawForm:json.getAsJsonArray("forms")) {
            var form=rawForm.getAsJsonObject(); if(!form.has("name")) continue;
            var inherited=json.deepCopy(); inherited.remove("forms");
            for(var value:form.entrySet()) {
                if(Set.of("name","aspects","forms","nationalPokedexNumber","implemented").contains(value.getKey())) continue;
                if(value.getKey().equals("baseStats") && value.getValue().isJsonObject() && inherited.has("baseStats"))
                    value.getValue().getAsJsonObject().entrySet().forEach(stat -> inherited.getAsJsonObject("baseStats").add(stat.getKey(),stat.getValue().deepCopy()));
                else inherited.add(value.getKey(),value.getValue().deepCopy());
            }
            SpeciesCatalog formCatalog=new SpeciesCatalog(); formCatalog.add(namespace,inherited);
            var entry=formCatalog.get(id); if(entry==null) continue;
            Set<String> aspects=new HashSet<>();
            if(form.has("aspects")) for(var aspect:form.getAsJsonArray("aspects")) aspects.add(FormNames.canonical(aspect.getAsString()));
            forms.add(new Variant(FormNames.canonical(form.get("name").getAsString()),Set.copyOf(aspects),entry));
        }
        variants.put(id,List.copyOf(forms));
    }

    public Entry get(String id) { return entries.get(GenerationRules.speciesKey(id)); }
    public Collection<Entry> entries() { return Collections.unmodifiableCollection(entries.values()); }

    public boolean allowed(GenerationRules rules, String properties) {
        Resolved resolved=resolve(properties);
        return resolved!=null && rules.allowed(resolved.entry.id,resolved.entry.dex,resolved.form,resolved.aspects);
    }

    public Resolved resolve(String properties) {
        if (properties == null || properties.isBlank()) return null;
        String[] pieces = properties.trim().toLowerCase(Locale.ROOT).split("\\s+");
        Entry entry = get(pieces[0]);
        if (entry == null) return null;
        Set<String> aspects = new HashSet<>();
        Set<String> matching=new HashSet<>();
        String form = null;
        for (int i = 1; i < pieces.length; i++) {
            aspects.add(pieces[i]);
            matching.add(FormNames.canonical(pieces[i]));
            if (pieces[i].startsWith("form=")) form = FormNames.canonical(pieces[i].substring(5));
            int split=pieces[i].indexOf('=');
            if(split>0) {
                String key=pieces[i].substring(0,split), value=pieces[i].substring(split+1);
                matching.add(FormNames.canonical(key+"-"+value));
                if(value.equals("true")) matching.add(FormNames.canonical(key));
                if(key.equals("mega_evolution")) matching.add(switch(value) { case "megax" -> "mega_x"; case "megay" -> "mega_y"; default -> FormNames.canonical(value); });
            }
        }
        for(var variant:variants.getOrDefault(entry.id,List.of())) {
            if(form!=null ? form.equals(variant.name) : !variant.aspects.isEmpty() && matching.containsAll(variant.aspects))
                return new Resolved(variant.entry,variant.name,Set.copyOf(aspects));
        }
        if(form!=null && !form.equals("normal")) return null;
        return new Resolved(entry,"normal",Set.copyOf(aspects));
    }

    public Entry replacement(GenerationRules rules, Entry source, Set<String> alreadyUsed,int level) {
        return entries.values().stream()
            .filter(e -> rules.allowed(e.id, e.dex,"normal",Set.of()) && e.levelMoves.stream().anyMatch(move -> Integer.parseInt(move.substring(0,move.indexOf(':')))<=level))
            .min(Comparator.comparingInt((Entry e) -> {
                int typeCost = source == null || !Collections.disjoint(e.types, source.types) ? 0 : 5000;
                int duplicateCost = alreadyUsed.contains(e.id) ? 1000 : 0;
                return typeCost + duplicateCost + Math.abs(e.strength - (source == null ? 350 : source.strength));
            }).thenComparing(Entry::id)).orElseThrow(() -> new IllegalStateException("Nenhuma espécie válida para adaptar uma equipe."));
    }
}
