package br.com.cobbleverse.generations.data;

import br.com.cobbleverse.generations.config.GenerationConfig;
import br.com.cobbleverse.generations.rules.GenerationRules;
import com.google.gson.*;

/** RCT prerequisites are AND-of-OR clauses. Excluded circuits cannot be mandatory prerequisites. */
public final class SeriesPolicy {
    private SeriesPolicy() {}
    public static boolean apply(String region,JsonObject series,GenerationRules rules) {
        if(!rules.filterGyms() || !rules.regionAllowed(region) || !series.has("requiredSeries")) return false;
        JsonArray filtered=new JsonArray();
        if(!rules.config().initialRegion.equals(region)) for(var clause:series.getAsJsonArray("requiredSeries")) {
            JsonArray options=new JsonArray();
            if(clause.isJsonArray()) {
                for(var value:clause.getAsJsonArray()) if(!GenerationConfig.REGIONS.contains(value.getAsString()) || rules.regionAllowed(value.getAsString())) options.add(value);
            } else if(!GenerationConfig.REGIONS.contains(clause.getAsString()) || rules.regionAllowed(clause.getAsString())) options.add(clause);
            if(!options.isEmpty()) filtered.add(options);
        }
        if(filtered.equals(series.get("requiredSeries"))) return false;
        series.add("requiredSeries",filtered);
        return true;
    }
}
