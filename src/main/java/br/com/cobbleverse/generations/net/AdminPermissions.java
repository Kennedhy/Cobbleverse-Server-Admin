package br.com.cobbleverse.generations.net;

import br.com.cobbleverse.generations.GenerationControl;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import java.lang.reflect.Method;

/** Optional Fabric Permissions API integration, with vanilla OP fallback. */
public final class AdminPermissions {
    private static boolean resolved;
    private static Method permissionsCheck;
    private AdminPermissions() {}

    public static boolean canEdit(ServerPlayerEntity player) { return canEdit(player.getCommandSource()); }
    public static boolean canEdit(ServerCommandSource source) {
        resolve();
        if (permissionsCheck != null) {
            try { return (Boolean)permissionsCheck.invoke(null,source,"generation_control.configure",3); }
            catch (ReflectiveOperationException e) {
                GenerationControl.LOGGER.error("Falha ao consultar permissões; alteração de configuração rejeitada.",e);
                return false;
            }
        }
        return source.hasPermissionLevel(3);
    }

    private static synchronized void resolve() {
        if (resolved) return;
        try {
            permissionsCheck = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions")
                .getMethod("check",ServerCommandSource.class,String.class,int.class);
        } catch (ClassNotFoundException e) { /* permissions plugin is optional */ }
        catch (NoSuchMethodException e) { GenerationControl.LOGGER.warn("Fabric Permissions API incompatível; usando níveis de operador."); }
        resolved = true;
    }
}
