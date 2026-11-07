package br.com.cobbleverse.generations.integration;

import net.fabricmc.loader.api.FabricLoader;
import java.util.*;

/** Mixins are tied to these inspected binaries. Unknown versions never silently count as covered. */
public final class Compatibility {
    private record Module(String id,String version,String scope) {}
    private static final List<Module> MODULES=List.of(
        new Module("minecraft","1.21.1","mundo e estruturas"),
        new Module("cobblemon","1.7.3+1.21.1","espécies, encontros, iniciais, armazenamento, evolução e fósseis"),
        new Module("rctapi","0.15.2-beta","equipes e séries"),
        new Module("rctmod","0.18.1-beta","líderes e progresso individual"),
        new Module("cobbreeding","2.2.2","reprodução e ovos"),
        new Module("cobblemonraiddens","0.11.3+1.21.1","chefes, recompensa, seleção e entrada em raid"),
        new Module("lumymon","0.6.6","altares e invocações"),
        new Module("legendarymonuments","Cobbleverse","monumentos, rituais e desafios"),
        new Module("cobblemon-additions","4.1.6","spawners de desafios"),
        new Module("mega_showdown","1.8.4+1.7.3+1.21.1","megavolução e formas primais"),
        new Module("zamega","1.7.3","formas pelo mecanismo Mega Showdown")
    );
    private Compatibility() {}
    public static void inspect(ValidationReport report) {
        var loader=FabricLoader.getInstance();
        for(var module:MODULES) {
            var container=loader.getModContainer(module.id());
            if(container.isEmpty()) { report.integrations.add(module.id()+": ausente; integração opcional inativa."); continue; }
            String actual=container.get().getMetadata().getVersion().getFriendlyString();
            if(!actual.equals(module.version())) {
                report.errors.add("Versão incompatível: "+module.id()+" "+actual+"; esperado "+module.version()+".");
                report.integrations.add(module.id()+": INCOMPATÍVEL; cobertura não confirmada.");
            } else report.integrations.add(module.id()+" "+actual+": versão reconhecida — "+module.scope()+".");
        }
    }
    public static void requireSupported() {
        var report=new ValidationReport();
        inspect(report);
        if(!report.errors.isEmpty()) throw new IllegalStateException(String.join(" ",report.errors));
    }
}
