package br.com.cobbleverse.generations.integration;

import java.util.*;

public final class ValidationReport {
    public int allowedSpecies;
    public int affectedPlayers;
    public int inspectedPlayers;
    public int playersWithoutUsablePokemon;
    public int affectedPokemon;
    public int playersWithReserve;
    public int reservePokemon;
    public int trainerTeams;
    public int adaptedTeams;
    public final List<String> affectedPlayerNames = new ArrayList<>();
    public final List<String> errors = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public final List<String> integrations = new ArrayList<>();
    public void clearPlayerImpact() {
        inspectedPlayers=0; affectedPlayers=0; playersWithoutUsablePokemon=0; affectedPokemon=0; playersWithReserve=0; reservePokemon=0;
        affectedPlayerNames.clear();
    }
    @Override public String toString() {
        return "Espécies permitidas: " + allowedSpecies + "; erros: " + errors + "; observações: " + warnings;
    }
}
