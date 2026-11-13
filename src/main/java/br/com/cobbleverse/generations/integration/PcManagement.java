package br.com.cobbleverse.generations.integration;

import br.com.cobbleverse.generations.GenerationControl;
import br.com.cobbleverse.generations.mixin.StoreCacheAccessor;
import br.com.cobbleverse.generations.mixin.StoreFactoryAccessor;
import br.com.cobbleverse.generations.mixin.StorePathAccessor;
import com.cobblemon.mod.common.api.abilities.Abilities;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.storage.adapter.flatfile.OneToOneFileStoreAdapter;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.pc.PCPosition;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.EVs;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.IVs;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Administrator-only PC changes, routed through Cobblemon's storage factory. */
public final class PcManagement {
    private static final int SLOT_COUNT = 30;
    private static final int DEFAULT_BOX_COUNT = 30;
    private static final Set<String> STAT_KEYS = Set.of("hp", "atk", "def", "spa", "spd", "spe");

    private PcManagement() {}

    public record Request(String action, String player, int box, int position, String expectedPokemonUuid,
                          String species, String form, Integer level, Boolean shiny, String nickname,
                          String nature, String ability, List<String> moves,
                          Map<String, Integer> ivs, Map<String, Integer> evs) {}

    public record Result(String action, UUID player, int box, int position, Future<?> persisted) {}

    public static final class Conflict extends IllegalStateException {
        public Conflict(String message) { super(message); }
    }

    public static final class Unavailable extends IllegalStateException {
        public Unavailable(String message) { super(message); }
    }

    public static final class Busy extends IllegalStateException {
        public Busy(String message) { super(message); }
    }

    /** Must run on the Minecraft server thread. Cached stores can be edited immediately. */
    public static Prepared prepare(MinecraftServer server, Request request, String actor) {
        Validated validated = validate(request);
        List<FileBackedPokemonStoreFactory<?>> factories;
        try { factories = PlayerImpact.providers(); }
        catch (IllegalStateException unsupported) { throw new Unavailable("O armazenamento do PC não está disponível para edição segura."); }

        StoreTarget target = findCached(factories, validated.player());
        if (target != null) return Prepared.immediate(applyToTarget(server, target, validated, actor));
        return Prepared.deferred(validated, factories, actor);
    }

    /** Wait on an HTTP worker so pending storage writes never stall a server tick. */
    public static void awaitPendingSaves(Prepared prepared) throws Exception {
        if (prepared.requiresQueueBarrier()) awaitSaveQueues(prepared.factories);
    }

    /** Must run on the Minecraft server thread after awaitPendingSaves. */
    public static Result applyDeferred(MinecraftServer server, Prepared prepared) throws Exception {
        if (!prepared.requiresQueueBarrier()) return prepared.immediate;
        StoreTarget target = findCached(prepared.factories, prepared.validated.player());
        if (target == null) target = loadSavedOrCreate(server, prepared.factories,
                prepared.validated.player(), prepared.validated.action());
        return applyToTarget(server, target, prepared.validated, prepared.actor);
    }

    private static Result applyToTarget(MinecraftServer server, StoreTarget target, Validated validated, String actor) {

        PCStore store = target.store();
        if (store.getBoxes().isEmpty() && validated.action().equals("add")) {
            store.resize(DEFAULT_BOX_COUNT, false, ignored -> kotlin.Unit.INSTANCE);
            store.initialize();
        }
        if (validated.box() >= store.getBoxes().size()) {
            if (validated.action().equals("add") && store.getBoxes().isEmpty() && validated.box() == 0) {
                store.resize(DEFAULT_BOX_COUNT, false, ignored -> kotlin.Unit.INSTANCE);
                store.initialize();
            } else {
                throw new Conflict("A caixa selecionada não existe mais. Atualize o PC e tente novamente.");
            }
        }

        PCPosition position = new PCPosition(validated.box(), validated.position());
        Pokemon current = store.get(position);
        switch (validated.action()) {
            case "add" -> {
                if (current != null) throw new Conflict("Esse espaço já foi ocupado. Atualize o PC antes de tentar novamente.");
                Pokemon created = createPokemon(validated);
                created.setOriginalTrainer(validated.player());
                store.set(position, created);
            }
            case "edit" -> {
                verifyExpected(current, validated.expectedPokemonUuid());
                Pokemon replacement = current.clone(false, server.getRegistryManager());
                applyProperties(replacement, validated);
                store.set(position, replacement);
            }
            case "delete" -> {
                verifyExpected(current, validated.expectedPokemonUuid());
                if (!store.remove(position)) throw new Conflict("O Pokémon não está mais nesse espaço. Atualize o PC e tente novamente.");
            }
            default -> throw new IllegalArgumentException("Ação de PC desconhecida.");
        }

        target.factory().save(store, server.getRegistryManager());
        Future<?> persisted = ((StoreFactoryAccessor) (Object) target.factory()).generationControl$executor().submit(() -> {});
        GenerationControl.LOGGER.info("Alteração de PC pelo painel: ação={}, jogador={}, caixa={}, espaço={}, autor={}",
                validated.action(), validated.player(), validated.box() + 1, validated.position() + 1, actor);
        return new Result(validated.action(), validated.player(), validated.box(), validated.position(), persisted);
    }

    public static final class Prepared {
        private final Validated validated;
        private final List<FileBackedPokemonStoreFactory<?>> factories;
        private final String actor;
        private final Result immediate;

        private Prepared(Validated validated, List<FileBackedPokemonStoreFactory<?>> factories, String actor, Result immediate) {
            this.validated = validated;
            this.factories = factories;
            this.actor = actor;
            this.immediate = immediate;
        }

        private static Prepared immediate(Result result) { return new Prepared(null, List.of(), null, result); }
        private static Prepared deferred(Validated validated, List<FileBackedPokemonStoreFactory<?>> factories, String actor) {
            return new Prepared(validated, factories, actor, null);
        }
        public boolean requiresQueueBarrier() { return immediate == null; }
        public Result immediateResult() { return immediate; }
    }

    private static void verifyExpected(Pokemon current, UUID expected) {
        if (current == null || expected == null || !current.getUuid().equals(expected))
            throw new Conflict("O Pokémon desse espaço mudou ou não pôde ser identificado. Atualize o PC e tente novamente.");
    }

    private static Pokemon createPokemon(Validated request) {
        PokemonProperties properties = properties(request, true, true);
        Pokemon pokemon = properties.create();
        Species species = requireSpecies(request.species());
        FormData form = resolveForm(species, request.form());
        if (!pokemon.getSpecies().getResourceIdentifier().equals(species.getResourceIdentifier())
                || !pokemon.getForm().formOnlyShowdownId().equalsIgnoreCase(form.formOnlyShowdownId()))
            throw new IllegalArgumentException("Não foi possível aplicar a espécie ou forma selecionada.");
        pokemon.setNickname(request.nickname().isBlank() ? null : Text.literal(request.nickname()));
        verifyApplied(pokemon, request);
        return pokemon;
    }

    private static void applyProperties(Pokemon pokemon, Validated request) {
        boolean resetLevelProgress = request.level() != pokemon.getLevel()
                || !request.species().equals(pokemon.getSpecies().getResourceIdentifier().toString());
        Species species = requireSpecies(request.species());
        FormData form = resolveForm(species, request.form());
        PokemonProperties properties = properties(request, false, resetLevelProgress);
        pokemon.setSpecies(species);
        pokemon.setForm(form);
        properties.apply(pokemon);
        pokemon.setNickname(request.nickname().isBlank() ? null : Text.literal(request.nickname()));
        verifyApplied(pokemon, request);
    }

    private static PokemonProperties properties(Validated request, boolean setSpeciesAndForm, boolean setLevel) {
        PokemonProperties properties = new PokemonProperties();
        if (setSpeciesAndForm) {
            properties.setSpecies(request.species());
            if (request.form() != null && !request.form().isBlank())
                properties.setForm(resolveForm(requireSpecies(request.species()), request.form()).formOnlyShowdownId());
        }
        if (setLevel) properties.setLevel(request.level());
        properties.setShiny(request.shiny());
        if (request.nature() != null && !request.nature().isBlank()) properties.setNature(normalizedId(request.nature()));
        if (request.ability() != null && !request.ability().isBlank()) properties.setAbility(normalizedName(request.ability()));
        if (request.moves() != null) properties.setMoves(request.moves());
        if (request.ivs() != null) properties.setIvs(toIvs(request.ivs()));
        if (request.evs() != null) properties.setEvs(toEvs(request.evs()));
        return properties;
    }

    private static void verifyApplied(Pokemon pokemon, Validated request) {
        if (request.ability() != null && !request.ability().isBlank()) {
            String expected = normalizedName(request.ability());
            String actual = pokemon.getAbility() == null ? "" : normalizedName(pokemon.getAbility().getName());
            if (!expected.equals(actual)) throw new IllegalArgumentException("Essa habilidade não é válida para a forma selecionada.");
        }
        if (request.moves() != null) {
            List<String> actual = pokemon.getMoveSet().getMoves().stream()
                    .map(move -> normalizedName(move.getTemplate().getName())).toList();
            List<String> expected = request.moves().stream().map(PcManagement::normalizedName).toList();
            if (!actual.equals(expected)) throw new IllegalArgumentException("Um ou mais golpes não puderam ser aplicados ao Pokémon.");
        }
    }

    private static Species requireSpecies(String raw) {
        Identifier id = Identifier.tryParse(raw);
        Species species = id == null ? null : PokemonSpecies.getByIdentifier(id);
        if (species == null) throw new IllegalArgumentException("Escolha uma espécie existente no catálogo carregado do servidor.");
        return species;
    }

    private static FormData resolveForm(Species species, String raw) {
        if (raw == null || raw.isBlank()) return species.getStandardForm();
        String formId = raw.trim();
        FormData form = species.getFormByShowdownId(formId);
        if (form == null) form = species.getFormByName(formId);
        if (form == null) throw new IllegalArgumentException("A forma informada não existe para essa espécie.");
        return form;
    }

    private static Validated validate(Request request) {
        if (request == null) throw new IllegalArgumentException("Dados da alteração ausentes.");
        String action = request.action() == null ? "" : request.action().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("add", "edit", "delete").contains(action)) throw new IllegalArgumentException("Ação de PC inválida.");
        UUID player = parseUuid(request.player(), "UUID do jogador inválido.");
        UUID expected = request.expectedPokemonUuid() == null || request.expectedPokemonUuid().isBlank()
                ? null : parseUuid(request.expectedPokemonUuid(), "UUID do Pokémon inválido.");
        if (request.box() < 0 || request.box() >= 4096 || request.position() < 0 || request.position() >= SLOT_COUNT)
            throw new IllegalArgumentException("Caixa ou espaço do PC inválido.");
        if ((action.equals("edit") || action.equals("delete")) && expected == null)
            throw new IllegalArgumentException("Recarregue a caixa antes de editar ou excluir um Pokémon.");
        if (action.equals("add") && expected != null)
            throw new IllegalArgumentException("O espaço de destino mudou. Atualize o PC antes de adicionar.");
        if (action.equals("delete")) return new Validated(action, player, request.box(), request.position(), expected,
                null, null, null, null, "", null, null, null, null, null);

        String species = bounded(request.species(), 128, "Espécie");
        requireSpecies(species);
        String form = nullableBounded(request.form(), 64, "Forma");
        resolveForm(requireSpecies(species), form);
        Integer level = request.level();
        if (level == null || level < 1 || level > 100) throw new IllegalArgumentException("O nível deve ficar entre 1 e 100.");
        if (request.shiny() == null) throw new IllegalArgumentException("Informe se o Pokémon será shiny.");
        String nickname = request.nickname() == null ? "" : bounded(request.nickname(), 64, "Apelido");
        String nature = nullableBounded(request.nature(), 64, "Natureza");
        if (nature != null && !nature.isBlank()) {
            Identifier id = Identifier.tryParse(normalizedId(nature));
            if (id == null || Natures.getNature(id) == null) throw new IllegalArgumentException("A natureza informada não existe.");
        }
        String ability = nullableBounded(request.ability(), 64, "Habilidade");
        if (ability != null && !ability.isBlank() && Abilities.get(normalizedName(ability)) == null)
            throw new IllegalArgumentException("A habilidade informada não existe.");
        List<String> moves = validateMoves(request.moves());
        Map<String, Integer> ivs = validateStats(request.ivs(), true);
        Map<String, Integer> evs = validateStats(request.evs(), false);
        return new Validated(action, player, request.box(), request.position(), expected, species, form, level,
                request.shiny(), nickname, nature, ability, moves, ivs, evs);
    }

    private static List<String> validateMoves(List<String> moves) {
        if (moves == null) return null;
        if (moves.size() > 4) throw new IllegalArgumentException("Um Pokémon pode ter no máximo quatro golpes.");
        List<String> result = new ArrayList<>(moves.size());
        Set<String> seen = new HashSet<>();
        for (String move : moves) {
            if (move == null || move.isBlank()) continue;
            String normalized = normalizedName(bounded(move, 64, "Golpe"));
            if (Moves.getByName(normalized) == null) throw new IllegalArgumentException("Golpe desconhecido: " + normalized + ".");
            if (!seen.add(normalized)) throw new IllegalArgumentException("Não repita o mesmo golpe.");
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static Map<String, Integer> validateStats(Map<String, Integer> values, boolean ivs) {
        if (values == null) return null;
        if (values.size() != STAT_KEYS.size()) throw new IllegalArgumentException("Informe os seis valores de IV/EV.");
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        int totalEvs = 0;
        for (String key : List.of("hp", "atk", "def", "spa", "spd", "spe")) {
            Integer value = values.get(key);
            if (value == null) throw new IllegalArgumentException("Informe os seis valores de IV/EV.");
            int maximum = ivs ? IVs.MAX_VALUE : EVs.MAX_STAT_VALUE;
            if (value < 0 || value > maximum) throw new IllegalArgumentException((ivs ? "IVs" : "EVs") + " fora do limite permitido.");
            totalEvs += value;
            result.put(key, value);
        }
        if (!STAT_KEYS.containsAll(values.keySet())) throw new IllegalArgumentException("Atributo de IV/EV desconhecido.");
        if (!ivs && totalEvs > EVs.MAX_TOTAL_VALUE) throw new IllegalArgumentException("A soma dos EVs não pode passar de 510.");
        return Map.copyOf(result);
    }

    private static IVs toIvs(Map<String, Integer> values) {
        IVs stats = new IVs();
        values.forEach((key, value) -> stats.set(stat(key), value));
        return stats;
    }

    private static EVs toEvs(Map<String, Integer> values) {
        EVs stats = new EVs();
        values.forEach((key, value) -> stats.set(stat(key), value));
        return stats;
    }

    private static Stats stat(String key) {
        return switch (key) {
            case "hp" -> Stats.HP;
            case "atk" -> Stats.ATTACK;
            case "def" -> Stats.DEFENCE;
            case "spa" -> Stats.SPECIAL_ATTACK;
            case "spd" -> Stats.SPECIAL_DEFENCE;
            case "spe" -> Stats.SPEED;
            default -> throw new IllegalArgumentException("Atributo de IV/EV desconhecido.");
        };
    }

    private static void awaitSaveQueues(List<FileBackedPokemonStoreFactory<?>> factories) throws Exception {
        List<Future<?>> barriers = new ArrayList<>(factories.size());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
        for (var factory : factories) {
            barriers.add(((StoreFactoryAccessor) (Object) factory).generationControl$executor().submit(() -> {}));
        }
        try {
            for (Future<?> barrier : barriers) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException();
                barrier.get(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (TimeoutException busy) {
            barriers.forEach(barrier -> barrier.cancel(false));
            throw new Busy("O armazenamento está ocupado salvando dados. Aguarde e tente novamente.");
        }
    }

    private static StoreTarget findCached(List<FileBackedPokemonStoreFactory<?>> factories, UUID player) {
        for (var factory : factories) {
            StoreFactoryAccessor access = (StoreFactoryAccessor) (Object) factory;
            Object cache = access.generationControl$caches().get(PCStore.class);
            if (cache == null) continue;
            Object store = ((StoreCacheAccessor) cache).generationControl$cacheMap().get(player);
            if (store instanceof PCStore pc) return new StoreTarget(factory, pc);
        }
        return null;
    }

    private static StoreTarget loadSavedOrCreate(MinecraftServer server, List<FileBackedPokemonStoreFactory<?>> factories,
                                                   UUID player, String action) throws Exception {
        for (var factory : factories) {
            StoreFactoryAccessor access = (StoreFactoryAccessor) (Object) factory;
            var adapter = access.generationControl$adapter();
            StorePathAccessor pathInfo = (StorePathAccessor) (Object) adapter;
            Path path = StorePaths.path(pathInfo.generationControl$root(), pathInfo.generationControl$nested(),
                    pathInfo.generationControl$perClass(), pathInfo.generationControl$extension(), PCStore.class.getSimpleName(), player);
            Path old = Path.of(path.toString() + ".old");
            if (Files.notExists(path) && Files.notExists(old)) continue;
            if (!(adapter instanceof OneToOneFileStoreAdapter<?> oneToOne))
                throw new Unavailable("O adaptador do PC não permite edição segura.");
            PCStore checked;
            try {
                checked = ReadOnlyStores.read(path, file -> {
                    if (file.length() > 16L * 1024 * 1024) throw new IOException("O arquivo excede 16 MiB.");
                    return loadSpecific(oneToOne, file, player, server);
                });
                if (checked == null || !checked.getUuid().equals(player))
                    throw new Unavailable("O arquivo do PC não corresponde ao jogador selecionado.");
            } catch (Unavailable failure) {
                throw failure;
            } catch (Exception failure) {
                throw new Unavailable("O PC salvo não pôde ser carregado com segurança; o arquivo foi preservado.");
            }
            if (server.getPlayerManager().getPlayer(player) != null) {
                Object cache = access.generationControl$caches().get(PCStore.class);
                if (cache != null) {
                    // Mirror FileBackedPokemonStoreFactory.getStore using the already validated decode.
                    checked.initialize();
                    factory.track(checked);
                    ((StoreCacheAccessor) cache).generationControl$cacheMap().put(player, checked);
                    return new StoreTarget(factory, checked);
                }
                PCStore live = factory.getPC(player, server.getRegistryManager());
                if (live == null) throw new Unavailable("O provedor do Cobblemon não conseguiu abrir o PC salvo.");
                return new StoreTarget(factory, live);
            }
            checked.initialize();
            return new StoreTarget(factory, checked);
        }

        if (!action.equals("add")) throw new Conflict("Nenhum PC salvo foi encontrado para esse jogador.");
        FileBackedPokemonStoreFactory<?> factory = factories.getFirst();
        if (server.getPlayerManager().getPlayer(player) != null) {
            PCStore live = factory.getPC(player, server.getRegistryManager());
            if (live == null) throw new Unavailable("O Cobblemon não conseguiu criar o PC do jogador.");
            return new StoreTarget(factory, live);
        }
        PCStore store = new PCStore(player);
        store.initialize();
        return new StoreTarget(factory, store);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PCStore loadSpecific(OneToOneFileStoreAdapter adapter, java.io.File file, UUID player, MinecraftServer server) {
        return (PCStore) adapter.load(file, PCStore.class, player, server.getRegistryManager());
    }

    private static UUID parseUuid(String raw, String message) {
        try {
            UUID value = UUID.fromString(raw);
            if (!value.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException(message); }
    }

    private static String bounded(String raw, int max, String label) {
        if (raw == null || raw.isBlank() || raw.length() > max) throw new IllegalArgumentException(label + " ausente ou muito longa.");
        return raw.trim();
    }

    private static String nullableBounded(String raw, int max, String label) {
        if (raw == null || raw.isBlank()) return null;
        return bounded(raw, max, label);
    }

    private static String normalizedName(String value) {
        String name = value.trim().toLowerCase(Locale.ROOT);
        int separator = name.indexOf(':');
        return separator >= 0 ? name.substring(separator + 1) : name;
    }

    private static String normalizedId(String value) {
        String id = value.trim().toLowerCase(Locale.ROOT);
        return id.contains(":") ? id : "cobblemon:" + id;
    }

    private record StoreTarget(FileBackedPokemonStoreFactory<?> factory, PCStore store) {}
    private record Validated(String action, UUID player, int box, int position, UUID expectedPokemonUuid,
                             String species, String form, Integer level, Boolean shiny, String nickname,
                             String nature, String ability, List<String> moves,
                             Map<String, Integer> ivs, Map<String, Integer> evs) {}
}
