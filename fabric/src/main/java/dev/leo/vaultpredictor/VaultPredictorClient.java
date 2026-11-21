package dev.leo.vaultpredictor;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class VaultPredictorClient implements ClientModInitializer {
    private static final int OFFSET_LIMIT = 100_000;
    private static final int FORECAST_LIMIT = 500;
    private static final List<String> TARGETS = Arrays.stream(VaultLoot.Item.values())
            .map(item -> item.token).distinct().sorted().toList();
    private static VaultPredictorClient instance;

    private Path profileFile;
    private VaultProfile profile;
    private Integer offset;
    private CompletableFuture<?> work;
    private VaultCapture capture;
    private ClientLevel captureLevel;
    private BlockPos capturePos;
    private Draft draft;

    private record Draft(List<String> loot, boolean complete) {}
    private record Forecast(int position, List<String> loot) {}

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            dispatcher.register(commands("ominusvaultpredictor"));
            dispatcher.register(commands("ovp"));
        });
        ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (level == captureLevel && capture != null && entity instanceof ItemEntity item) {
                updateCapture(Minecraft.getInstance());
                if (capture.observe(item.getUUID(), item.getX(), item.getY(), item.getZ())) itemData(level, item);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (capture != null) updateCapture(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetSession());
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> commands(String name) {
        return literal(name).executes(c -> help())
                .then(literal("help").executes(c -> help()))
                .then(literal("seed").then(argument("seed", word())
                        .executes(c -> run(() -> seed(getString(c, "seed"))))))
                .then(literal("capture")
                        .then(literal("start").executes(c -> run(this::start)))
                        .then(literal("stop").executes(c -> run(this::stop)))
                        .then(literal("save").executes(c -> run(this::save)))
                        .then(literal("cancel").executes(c -> run(this::cancel)))
                        .then(literal("edit").then(argument("loot", greedyString())
                                .executes(c -> run(() -> edit(getString(c, "loot")))))))
                .then(literal("log").executes(c -> run(this::log)))
                .then(literal("remove").then(argument("number", integer(1))
                        .executes(c -> run(() -> remove(getInteger(c, "number"))))))
                .then(literal("clear").executes(c -> run(this::clear)))
                .then(literal("recover").executes(c -> run(this::recover)))
                .then(literal("predict").then(argument("item", word()).suggests((c, suggestions) -> {
                    for (String item : TARGETS) {
                        if (item.startsWith(suggestions.getRemainingLowerCase())) suggestions.suggest(item);
                    }
                    return suggestions.buildFuture();
                }).executes(c -> run(() -> predict(getString(c, "item"))))));
    }

    private int help() {
        chat("/ominusvaultpredictor or /ovp");
        chat("/ovp seed <seed> · /ovp capture start | stop | save | cancel");
        chat("/ovp capture edit <loot> · /ovp log · /ovp remove <number> · /ovp clear");
        chat("/ovp recover · /ovp predict <item> (example: heavy_core)");
        return 1;
    }

    @FunctionalInterface
    private interface Operation { void execute() throws IOException; }

    private int run(Operation operation) {
        try {
            loadProfile();
            operation.execute();
            return 1;
        } catch (IOException ex) {
            chat("Could not access vault history. " + ex.getMessage());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            chat(ex.getMessage());
        }
        return 0;
    }

    private void loadProfile() throws IOException {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) throw new IllegalStateException("Join a world first.");
        String worldKey;
        var singleplayer = client.getSingleplayerServer();
        if (singleplayer != null) {
            worldKey = "save:" + singleplayer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        } else if (client.getCurrentServer() != null) {
            ServerAddress address = ServerAddress.parseString(client.getCurrentServer().ip);
            String host = address.getHost().toLowerCase(Locale.ROOT);
            if (host.contains(":")) host = "[" + host + "]";
            worldKey = "server:" + host + ":" + address.getPort();
        } else {
            throw new IllegalStateException("This connection has no saved server address for a vault profile.");
        }
        Path file = VaultProfile.file(FabricLoader.getInstance().getConfigDir().resolve("ominousvaultpredictor"), worldKey);
        if (file.equals(profileFile)) return;
        resetSession();
        profile = VaultProfile.read(file);
        profileFile = file;
    }

    private void store(VaultProfile next) throws IOException {
        next.write(profileFile);
        profile = next;
        offset = null;
    }

    private void seed(String text) throws IOException {
        long value;
        try {
            value = OminousVaultPredictor.parseSeedString(text);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Enter a valid world seed as a decimal integer or 0x hex value.");
        }
        if (!Objects.equals(profile.seed(), value)) store(new VaultProfile(value, profile.observations()));
        chat("World seed set to " + value + ".");
    }

    private void start() {
        if (capture != null || draft != null) throw new IllegalStateException("Stop, save, or cancel the current capture first.");
        Minecraft client = Minecraft.getInstance();
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            throw new IllegalStateException("Look directly at an ominous vault first.");
        }
        BlockPos pos = hit.getBlockPos().immutable();
        BlockState state = client.level.getBlockState(pos);
        if (!isOminousVault(state)) throw new IllegalStateException("Look directly at an ominous vault first.");
        VaultState vaultState = state.getValue(VaultBlock.STATE);
        if (vaultState == VaultState.UNLOCKING || vaultState == VaultState.EJECTING) {
            throw new IllegalStateException("Start capture before inserting the ominous trial key.");
        }
        capture = new VaultCapture(pos.getX(), pos.getY(), pos.getZ());
        captureLevel = client.level;
        capturePos = pos;
        chat("Vault " + nextNumber() + " capture started. Insert the key and wait for the vault to finish ejecting loot.");
    }

    private void updateCapture(Minecraft client) {
        if (capture.complete()) return;
        if (client.level != captureLevel || !captureLevel.hasChunkAt(capturePos)
                || !isOminousVault(captureLevel.getBlockState(capturePos))) {
            capture.interrupt();
            return;
        }
        capture.updateVault(captureLevel.getBlockState(capturePos).getValue(VaultBlock.STATE) == VaultState.EJECTING);
    }

    private static boolean isOminousVault(BlockState state) {
        return state.is(Blocks.VAULT) && state.getValue(VaultBlock.OMINOUS);
    }

    public static void itemData(ClientLevel level, ItemEntity item) {
        if (instance == null || instance.capture == null || instance.captureLevel != level
                || !instance.capture.pending(item.getUUID()) || item.getItem().isEmpty()) return;
        String token = VaultItemStack.token(item.getItem());
        if (token == null) instance.capture.interrupt();
        else instance.capture.record(item.getUUID(), token);
    }

    public static void ejection(ClientLevel level, BlockPos pos) {
        if (instance != null && instance.capture != null && instance.captureLevel == level
                && pos.equals(instance.capturePos)) instance.capture.ejected();
    }

    private void stop() {
        if (capture == null) throw new IllegalStateException("Start a vault capture first.");
        updateCapture(Minecraft.getInstance());
        draft = new Draft(capture.loot(), capture.complete());
        clearCapture();
        chat("Vault " + nextNumber() + " captured: " + summary(draft.loot()));
        chat(draft.complete() ? "Review the drops, then use /ovp capture save or /ovp capture cancel."
                : "Capture may be incomplete. Use /ovp capture edit <loot> to supply the full loot, or cancel.");
    }

    private void edit(String text) {
        if (draft == null) throw new IllegalStateException("Stop capture before editing the loot.");
        String invalidLoot = "Enter all loot with semicolons between items (emerald x6; ominous_bottle IV; heavy_core).";
        List<String> loot = VaultLoot.split(text).stream()
                .map(token -> VaultLoot.parse(token).orElseThrow(() -> new IllegalArgumentException(invalidLoot)).token())
                .toList();
        if (loot.isEmpty()) throw new IllegalArgumentException(invalidLoot);
        draft = new Draft(loot, true);
        chat("Vault " + nextNumber() + " updated: " + summary(loot));
    }

    private void save() throws IOException {
        if (draft == null) throw new IllegalStateException("Stop capture before saving.");
        if (!draft.complete()) throw new IllegalStateException("Correct the incomplete capture with /ovp capture edit first.");
        int number = nextNumber();
        List<String> entries = new ArrayList<>(profile.observations());
        entries.add(String.join("; ", draft.loot()));
        store(new VaultProfile(profile.seed(), entries));
        draft = null;
        chat("Vault " + number + " saved.");
    }

    private void cancel() {
        if (draft == null && capture == null) throw new IllegalStateException("There is no capture to cancel.");
        clearCapture();
        draft = null;
        chat("Vault capture cancelled.");
    }

    private int nextNumber() { return profile.observations().size() + 1; }

    private void log() {
        if (profile.observations().isEmpty()) chat("No vaults logged.");
        for (int i = 0; i < profile.observations().size(); i++) {
            chat("Vault " + (i + 1) + ": " + summary(VaultLoot.split(profile.observations().get(i))));
        }
    }

    private void remove(int number) throws IOException {
        requireNoCapture();
        List<String> entries = new ArrayList<>(profile.observations());
        if (number > entries.size()) throw new IllegalArgumentException("No saved vault " + number + ".");
        entries.remove(number - 1);
        store(new VaultProfile(profile.seed(), entries));
        chat("Vault " + number + " removed. Keep a consecutive history for recovery.");
    }

    private void clear() throws IOException {
        requireNoCapture();
        store(new VaultProfile(profile.seed(), List.of()));
        chat("Vault history cleared.");
    }

    private void requireNoCapture() {
        if (capture != null || draft != null) throw new IllegalStateException("Save or cancel the current capture first.");
    }

    private long knownSeed() {
        if (profile.seed() == null) throw new IllegalStateException("Set the known seed with /ovp seed <seed> first.");
        return profile.seed();
    }

    private void recover() {
        long seed = knownSeed();
        var observations = profile.observations().stream().map(VaultLoot::split).toList();
        if (observations.isEmpty()) throw new IllegalStateException("Log at least one vault first.");
        background("Recovering the vault offset...", () -> OminousVaultPredictor.recoverOffset(
                OminousVaultPredictor.VaultRng.fromSeed(seed), observations, OFFSET_LIMIT), result -> {
            if (result == OminousVaultPredictor.AMBIGUOUS_OFFSET) chat("Multiple offsets matched. Capture another consecutive vault.");
            else if (result == OminousVaultPredictor.NO_OFFSET) chat("No match in the first 100,000 offsets. Check the seed, loot, and opening order.");
            else {
                offset = result;
                chat("Recovered offset " + offset + ". Total opened through the last logged vault: " + (offset + observations.size()) + ".");
            }
        });
    }

    private void predict(String item) {
        long seed = knownSeed();
        if (!TARGETS.contains(item)) throw new IllegalArgumentException("Choose a loot item from command suggestions, such as heavy_core.");
        if (offset == null && !profile.observations().isEmpty()) {
            throw new IllegalStateException("Run /ovp recover with your current history before forecasting.");
        }
        int skip = offset == null ? 0 : offset + profile.observations().size();
        background(offset == null ? "Forecasting from the first vault for this seed..." : "Forecasting from your recovered position...",
                () -> forecast(seed, skip, item), result -> {
            if (result == null) chat("No " + item + " within the next " + FORECAST_LIMIT + " vaults.");
            else chat(item + " is in upcoming vault " + result.position() + ": " + summary(result.loot()));
        });
    }

    private static Forecast forecast(long seed, int skip, String item) {
        var rng = OminousVaultPredictor.VaultRng.fromSeed(seed);
        for (int i = 0; i < skip; i++) OminousVaultPredictor.simulateVault(rng);
        for (int i = 1; i <= FORECAST_LIMIT; i++) {
            var loot = OminousVaultPredictor.simulateVault(rng).items();
            if (loot.stream().anyMatch(token -> token.equals(item) || token.startsWith(item + " ") || token.startsWith(item + "["))) {
                return new Forecast(i, loot);
            }
        }
        return null;
    }

    private <T> void background(String message, Supplier<T> task, Consumer<T> show) {
        if (work != null) throw new IllegalStateException("A vault calculation is already running.");
        chat(message);
        var input = profile;
        var client = Minecraft.getInstance();
        var future = CompletableFuture.supplyAsync(task);
        work = future;
        future.whenComplete((value, error) -> client.execute(() -> {
            if (work != future) return;
            work = null;
            if (profile != input) chat("Vault history changed during the calculation. Run the command again.");
            else if (error != null) chat("Vault calculation failed. " + error.getCause());
            else show.accept(value);
        }));
    }

    private void clearCapture() {
        capture = null;
        captureLevel = null;
        capturePos = null;
    }

    private void resetSession() {
        clearCapture();
        draft = null;
        profile = null;
        profileFile = null;
        offset = null;
        work = null;
    }

    private static String summary(List<String> loot) {
        return loot.isEmpty() ? "no loot detected" : loot.stream().map(VaultLoot::display).collect(Collectors.joining(", "));
    }

    private static void chat(String message) {
        Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[OVP] " + message));
    }
}
