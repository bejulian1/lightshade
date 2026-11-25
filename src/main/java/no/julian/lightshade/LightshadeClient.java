package no.julian.lightshade;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import no.julian.lightshade.config.LightshadeConfig;
import no.julian.lightshade.pack.PackManager;
import no.julian.lightshade.render.LightshadeRenderer;
import no.julian.lightshade.ui.LightshadePackScreen;
import no.julian.lightshade.ui.LightshadeSettingsScreen;

/** Lightshade Shader Loader bootstrap: config, pack folder, O keybind, and the guarded first apply. */
public final class LightshadeClient implements ClientModInitializer {
    public static final String MOD_ID = "lightshade";
    private static final System.Logger LOG = System.getLogger("Lightshade");
    /** Other shader loaders that replace the same terrain programs and render passes. */
    private static final Set<String> CONFLICTS = Set.of("sulkan", "iris", "oculus", "optifabric");
    private static LightshadeConfig config;
    private static PackManager packs;
    private static volatile String guard, startupError;
    private static volatile boolean applying;

    @Override public void onInitializeClient() {
        Path game = FabricLoader.getInstance().getGameDir();
        config = new LightshadeConfig(FabricLoader.getInstance().getConfigDir().resolve("lightshade.json"));
        config.load();
        packs = new PackManager(game.resolve("shaderpacks"));
        List<String> loaded = FabricLoader.getInstance().getAllMods().stream().map(mod -> mod.getMetadata().getId()).toList();
        String conflict = conflict(loaded);
        if (conflict != null) {
            guard = "Lightshade is switched off because another shader loader (" + conflict + ") is installed. Remove one of them and restart.";
            LOG.log(System.Logger.Level.WARNING, guard);
        }
        KeyMapping open = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lightshade.open",
            InputConstants.Type.KEYBOARD, InputConstants.KEY_O,
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "settings"))));
        ClientTickEvents.END_CLIENT_TICK.register(client -> { while (open.consumeClick()) openFromKey(); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> LightshadeRenderer.close());
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (guard != null) return;
            try {
                LightshadeRenderer.configure(packs.directory());
                LightshadeConfig.State saved = config.state();
                if (saved.enabled()) {
                    applying = true;
                    LightshadeRenderer.apply(saved.selectedPack(), saved.options(saved.selectedPack())).whenComplete((unused, error) -> {
                        applying = false;
                        if (error != null) {
                            startupError = "Lightshade could not start the saved pack: " + reason(error);
                            LOG.log(System.Logger.Level.WARNING, startupError, error);
                        }
                    });
                } else LightshadeRenderer.disable();
            } catch (Throwable failure) {
                applying = false;
                startupError = "Lightshade failed to start: " + reason(failure);
                LOG.log(System.Logger.Level.WARNING, startupError, failure);
            }
        });
    }
    /** The first loaded mod that is a known shader loader, or any other "*_shader_loader". */
    public static String conflict(Collection<String> modIds) {
        for (String id : modIds) {
            if (id.equals(MOD_ID)) continue;
            if (CONFLICTS.contains(id) || id.endsWith("_shader_loader") || id.endsWith("shaderloader")) return id;
        }
        return null;
    }
    public static boolean allowed() { return guard == null; }
    public static String guardReason() { return guard; }
    public static LightshadeConfig config() { return config; }
    public static PackManager packs() { return packs; }
    public static boolean applying() { return applying; }
    public static String problemText() {
        if (guard != null) return guard;
        if (startupError != null) return startupError;
        if (config.loadError() != null) return config.loadError();
        if (!config.state().enabled()) return null;
        Object problem = LightshadeRenderer.status().get("failure");
        return problem instanceof String text && !text.isBlank() ? text : null;
    }
    public static void openMenu(Screen parent) { Minecraft.getInstance().gui.setScreen(new LightshadePackScreen(parent)); }
    private static void openFromKey() {
        Minecraft client = Minecraft.getInstance();
        Screen current = client.gui.screen();
        if (config != null && !(current instanceof LightshadePackScreen) && !(current instanceof LightshadeSettingsScreen)) openMenu(current);
    }
    public static CompletableFuture<Void> apply(String packId, boolean enabled, Map<String,Double> options) {
        if (guard != null) return CompletableFuture.failedFuture(new IllegalStateException(guard));
        if (applying) return CompletableFuture.failedFuture(new IllegalStateException("A shader compilation is already running."));
        Map<String,Double> validated;
        try { validated = packs.describe(packId).accepted(options); }
        catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
        LightshadeConfig.State previous = config.state();
        LightshadeConfig.State next = previous.selected(enabled, packId, validated);
        applying = true;
        CompletableFuture<Void> result = new CompletableFuture<>();
        CompletableFuture<Void> compiled;
        try {
            if (enabled) compiled = LightshadeRenderer.apply(packId, validated);
            else { LightshadeRenderer.disable(); compiled = CompletableFuture.completedFuture(null); }
        } catch (Throwable failure) { applying = false; return CompletableFuture.failedFuture(failure); }
        compiled.whenComplete((unused, failure) -> Minecraft.getInstance().execute(() -> {
            if (failure != null) {
                applying = false; result.completeExceptionally(failure); return;
            }
            try {
                config.save(next);
                startupError = null;
                applying = false;
                result.complete(null);
            } catch (Exception saveFailure) {
                // Restore the previous live selection if persistence fails.
                CompletableFuture<Void> rollback;
                try {
                    if (previous.enabled()) rollback = LightshadeRenderer.apply(previous.selectedPack(), previous.options(previous.selectedPack()));
                    else { LightshadeRenderer.disable(); rollback = CompletableFuture.completedFuture(null); }
                } catch (Throwable rollbackFailure) { rollback = CompletableFuture.failedFuture(rollbackFailure); }
                rollback.whenComplete((ignored, rollbackFailure) -> Minecraft.getInstance().execute(() -> {
                    applying = false;
                    if (rollbackFailure != null) {
                        saveFailure.addSuppressed(rollbackFailure);
                        LightshadeRenderer.disable();
                        startupError = "Lightshade settings could not be saved or restored. Shaders are off.";
                    }
                    result.completeExceptionally(saveFailure);
                }));
            }
        }));
        return result;
    }
    public static String reason(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause() != null)
            failure = failure.getCause();
        return Objects.toString(failure.getMessage(), failure.getClass().getSimpleName());
    }
}
