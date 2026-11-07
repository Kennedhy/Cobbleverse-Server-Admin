package br.com.cobbleverse.generations.data;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GymStructuresTest {
    @Test void identifiesGymAndLeagueStructuresAcrossTheFourRegions() {
        for (String[] group : new String[][] {
            {"kanto","blaine","brock","erika","giovanni","koga","kanto_league","ltsurge","misty","sabrina"},
            {"johto","alfredo","angelo","chiara","furio","jasmine","johto_league","raffaello","sandra","valerio"},
            {"hoenn","adriano","alice","fiammetta","hoenn_league","norman","petra","rudi","tell_pat","walter"},
            {"sinnoh","bianca","corrado","fannie","ferruccio","gardenia","sinnoh_league","marzia","omar","pedro"}
        }) for(int i=1;i<group.length;i++) assertEquals(group[0],GymStructures.region("worldgen/structure/"+group[i]+".json"));
    }
    @Test void leavesOtherRegionalStructuresAndTagsAlone() {
        for(String name:new String[]{"bell_tower","celebi_shrine","whirl_island","groudon","kyogre","sky_pillar","spear_pillar","manaphy","team_galactic_hq"})
            assertNull(GymStructures.region("worldgen/structure/"+name+".json"));
        assertNull(GymStructures.region("tags/worldgen/structure/johto_alfredo_gym.json"));
        assertNull(GymStructures.region("worldgen/structure/johto/alfredo.json"));
    }
}
