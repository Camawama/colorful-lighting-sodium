package net.camacraft.colorfullighting.event;

import com.mojang.brigadier.CommandDispatcher;
import net.camacraft.colorfullighting.ColorfulLighting;
import net.camacraft.colorfullighting.common.*;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelAttachments;
import net.camacraft.colorfullighting.common.accessors.mixin.LevelRendererAccessor;
import net.camacraft.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import net.camacraft.colorfullighting.compat.flywheel.FlywheelCompat;
import net.camacraft.colorfullighting.compat.oculus.ShaderpackAutoPatcher;
import net.camacraft.colorfullighting.compat.oculus.cmd.PackArgumentType;
import net.camacraft.colorfullighting.compat.oculus.cmd.ShaderPackName;
import net.camacraft.colorfullighting.compat.valkyrienskies.VsCompat;
import net.camacraft.colorfullighting.compat.distanthorizons.DhCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

public class ClientEventListener {
    private boolean wasShaderPackInUse = false;
    private String lastShaderPackName = null;

    @SubscribeEvent
    public void onTick(TickEvent.LevelTickEvent event) {
	    if (event.side != LogicalSide.CLIENT) return;
	    // LevelTickEvent fires at START and END; without this gate the whole body ran twice
	    // per tick (a solid ~5% of render-thread time in profiling, mostly the NBT cache scan)
	    if (event.phase != TickEvent.Phase.END) return;

	    if (ColorfulLighting.clientAccessor == null) return;
	    var player = ColorfulLighting.clientAccessor.getPlayer();
	    if (player == null) return;
	    
	    // Snapshot this level's dynamic light sources (SodiumDynamicLights, luminous entities)
	    if (event.level instanceof ClientLevel clientLevel) {
		    DynamicLightsCompat dynamicLights = ((LevelAttachments) clientLevel).colorfullighting$getDynamicLights();
		    if (dynamicLights != null) dynamicLights.clientTick(clientLevel);
	    }
		
//	    ChunkPos pos = player.getChunkPos();
//	    int renderDistance = ColorfulLighting.clientAccessor.getRenderDistance();
//	    ViewArea viewArea = new ViewArea(
//			    pos.x - renderDistance,
//			    pos.z - renderDistance,
//			    pos.x + renderDistance,
//			    pos.z + renderDistance
//	    );
	    
	    LevelAttachments attachments = (LevelAttachments) event.level;
		ColoredLightEngine engine = attachments.colorfullighting$getEngine();
//		engine.updateViewArea(viewArea);
	    engine.tick();

	    // Keep a light region alive for every loaded Valkyrien Skies ship (no-op without VS).
	    VsCompat compat = attachments.colorfullighting$getVSCompat();
		if (compat != null) compat.clientTick(event.level);
	    
	    // Re-reads tracked block entities' NBT and relights the ones whose resolved light changed.
	    attachments.colorfullighting$getNbtCache().clientTick();
    }
	
    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) return;

        // Check for Oculus shader state changes
//        if (OculusCompat.isOculusLoaded()) {
//            boolean isShaderPackInUse = OculusCompat.isShaderPackInUse();
//            String packName = isShaderPackInUse ? OculusCompat.getCurrentShaderPackName() : null;
//            if (isShaderPackInUse != wasShaderPackInUse || !java.util.Objects.equals(packName, lastShaderPackName)) {
//                wasShaderPackInUse = isShaderPackInUse;
//                lastShaderPackName = packName;
//                if (isShaderPackInUse) {
//                    // Packs carrying the Colorful Lighting patch marker decode the packed
//                    // lightmap format themselves, so the engine can stay on.
//                    boolean patched = OculusCompat.isShaderPackPatched(packName);
//                    ColoredLightEngine.setEnabled(true);
//                    if (patched) {
//                        ColorfulLighting.LOGGER.info("Oculus shader '{}' is Colorful Lighting patched, keeping colored lighting enabled", packName);
//                    } else {
//                        ColorfulLighting.LOGGER.info("Oculus shader '{}' enabled, disabling colored lighting (no Colorful Lighting patch found)", packName);
//                    }
//                } else {
//                    ColoredLightEngine.setEnabled(true);
//                    ColorfulLighting.LOGGER.info("Oculus shader disabled, enabling colored lighting");
//                }
//                if (Minecraft.getInstance().levelRenderer != null) {
//                    Minecraft.getInstance().levelRenderer.allChanged();
//                }
//            }
//        }

        // Keeps the DH color cache's active-level pointer fresh and autosaves it (no-op without DH)
        DhCompat.clientTick();

        if (ColorfulLighting.clientAccessor == null) return;
        var player = ColorfulLighting.clientAccessor.getPlayer();
        if (player == null) return;

    }

    /**
     * Fired after the chunk is registered with the chunk cache, so block states resolve here — unlike
     * inside LevelChunk#replaceWithPacketData, where the block entities are actually created.
     */
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide()) return;
        if (event.getChunk() instanceof LevelChunk chunk) {
	        ((LevelAttachments) event.getLevel()).colorfullighting$getNbtCache().onChunkLoaded(chunk);
        }
    }

    @SubscribeEvent
    public void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            LevelRenderer levelRenderer = Minecraft.getInstance().levelRenderer;
            if (levelRenderer != null) {
                ((LevelAttachments) ((LevelRendererAccessor) levelRenderer).colorfullighting$getClientLevel()).colorfullighting$getEngine().updateFrustum(levelRenderer.getFrustum());
            }
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        if (event.getLevel() instanceof net.minecraft.world.level.Level level) {
            DhCompat.onLevelUnload(level);
        }
	    ((LevelAttachments) event.getLevel()).colorfullighting$getNbtCache().clear();
        BeaconEffectSync.clear();
		// I think this is redundant
        ((LevelAttachments) event.getLevel()).colorfullighting$getEngine().reset();
		// TODO: attach this to a Cleaner as well for redundancy/safety reasons
	    ((LevelAttachments) event.getLevel()).colorfullighting$getEngine().unload();
    }

    @SubscribeEvent
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("cl")
                        .then(Commands.literal("purge")
                                .then(Commands.literal("chunk")
                                        .executes(context -> {
                                            var player = Minecraft.getInstance().player;
                                            if (player != null) {
                                                ((LevelAttachments) player.level()).colorfullighting$getEngine().rebuildChunk(player.chunkPosition());
                                                context.getSource().sendSuccess(() -> Component.literal("Reloading colored light in 3x3 chunk radius..."), false);
                                            }
                                            return 1;
                                        })
                                )
                                .then(Commands.literal("all")
                                        .executes(context -> {
                                            ColoredLightEngine.resetAll();
                                            if (Minecraft.getInstance().levelRenderer != null) {
                                                Minecraft.getInstance().levelRenderer.allChanged();
                                            }
                                            context.getSource().sendSuccess(() -> Component.literal("Reloading all colored lights..."), false);
                                            return 1;
                                        })
                                )
                                .executes(context -> {
                                    // Default behavior (same as 'all') for backward compatibility
                                    ColoredLightEngine.resetAll();
                                    if (Minecraft.getInstance().levelRenderer != null) {
                                        Minecraft.getInstance().levelRenderer.allChanged();
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal("Reloading all colored lights..."), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("patchshader")
		                        .then(
				                        Commands.argument("shader_pack", new PackArgumentType())
				                        .executes(context -> {
					                        ShaderPackName pck = context.getArgument("shader_pack", ShaderPackName.class);
											
					                        context.getSource().sendSuccess(() -> Component.literal("Patching shaderpacks for Colorful Lighting..."), false);
					                        ShaderpackAutoPatcher.runAsync(pck.getName(), message -> {
						                        var minecraft = Minecraft.getInstance();
						                        minecraft.execute(() -> {
							                        if (minecraft.player != null) {
								                        minecraft.player.displayClientMessage(Component.literal(message), false);
							                        }
						                        });
					                        });
					                        return 1;
				                        })
		                        )
                        )
                        .then(Commands.literal("on")
                                .executes(context -> {
                                    ColoredLightEngine.setEnabled(true);
                                    if (Minecraft.getInstance().levelRenderer != null) {
                                        Minecraft.getInstance().levelRenderer.allChanged();
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal("Colored lighting enabled"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("off")
                                .executes(context -> {
                                    ColoredLightEngine.setEnabled(false);
                                    if (Minecraft.getInstance().levelRenderer != null) {
                                        Minecraft.getInstance().levelRenderer.allChanged();
                                    }
                                    context.getSource().sendSuccess(() -> Component.literal("Colored lighting disabled"), false);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("dh")
                                .executes(context -> {
                                    for (String line : DhCompat.describeStatus().split("\n")) {
                                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                                    }
                                    return 1;
                                })
                                .then(Commands.literal("on")
                                        .executes(context -> setDhLodColor(context.getSource(), true))
                                )
                                .then(Commands.literal("off")
                                        .executes(context -> setDhLodColor(context.getSource(), false))
                                )
                                .then(Commands.literal("debug")
                                        .executes(context -> {
                                            int mode = DhCompat.getDebugMode() == 0 ? 1 : 0;
                                            return setDhDebugMode(context.getSource(), mode);
                                        })
                                        .then(Commands.argument("mode", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 5))
                                                .executes(context -> setDhDebugMode(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "mode")))
                                        )
                                )
                        )
                        // Diagnostic and power-user commands live under one literal so the
                        // top-level autocomplete stays a short list: on, off, purge, debug.
                        .then(Commands.literal("debug")
                                // Live queue/thread state for the "light stops until /cl purge" bug:
                                // run it WHILE the light is broken, before purging.
                                .then(Commands.literal("queue")
                                        .executes(context -> {
                                            var player = Minecraft.getInstance().player;
                                            if (player != null) {
                                                String report = ((LevelAttachments) player.level()).colorfullighting$getEngine()
                                                        .describeQueues(player.chunkPosition());
                                                ColorfulLighting.LOGGER.info("[CL queue] {}", report);
                                                context.getSource().sendSuccess(() -> Component.literal(report), false);
                                            }
                                            return 1;
                                        })
                                )
                                .then(Commands.literal("patchshaders")
                                        .executes(context -> {
                                            context.getSource().sendSuccess(() -> Component.literal("Patching shaderpacks for Colorful Lighting..."), false);
                                            ShaderpackAutoPatcher.runAsync(message -> {
                                                var minecraft = Minecraft.getInstance();
                                                minecraft.execute(() -> {
                                                    if (minecraft.player != null) {
                                                        minecraft.player.displayClientMessage(Component.literal(message), false);
                                                    }
                                                });
                                            });
                                            return 1;
                                        })
                                )
                                .then(Commands.literal("flywheel")
                                        .executes(context -> {
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    FlywheelCompat.describeMode()), false);
                                            boolean forced = ColorfulLightingConfig.flywheelForceTextureMode();
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "Configured: " + (forced ? "texture (forced for testing)" : "auto (SSBO when the GPU supports GLSL 430)")
                                                            + " — change with /cl debug flywheel texture|auto"), false);
                                            if (forced
                                                    && FlywheelCompat.isAvailable()
                                                    && !FlywheelCompat.isTextureFallback()) {
                                                context.getSource().sendSuccess(() -> Component.literal(
                                                        "WARNING: texture mode is configured but SSBO mode is active — the setting was read after flywheel initialized. Add -Dcolorfullighting.flywheelForceTextureMode=true to your JVM arguments instead."), false);
                                            }
                                            return 1;
                                        })
                                        .then(Commands.literal("report")
                                                .executes(context -> {
                                                    String report = !FlywheelCompat.isAvailable()
                                                            ? "Flywheel colored light is inactive"
                                                            : FlywheelCompat.debugReportAll();
                                                    ColorfulLighting.LOGGER.info("[CL flywheel] {}", report);
                                                    context.getSource().sendSuccess(() -> Component.literal(report), false);
                                                    return 1;
                                                })
                                        )
                                        .then(Commands.literal("texture")
                                                .executes(context -> setFlywheelTextureMode(context.getSource(), true))
                                        )
                                        .then(Commands.literal("ssbo")
                                                .executes(context -> setFlywheelTextureMode(context.getSource(), false))
                                        )
                                        .then(Commands.literal("auto")
                                                .executes(context -> setFlywheelTextureMode(context.getSource(), false))
                                        )
                                )
                        )
        );
    }

    private static int setDhDebugMode(CommandSourceStack source, int mode) {
        DhCompat.setDebugMode(mode);
        String description = switch (mode) {
            case 1 -> "1: color memory coverage (red = beyond range, blue = nothing remembered, else remembered light)";
            case 2 -> "2: geometry check (world-position stripes; should look like a 16-block 3D checker on the terrain)";
            case 3 -> "3: albedo only";
            case 4 -> "4: vanilla lightmap only";
            case 5 -> "5: light coords (red = block light, green = sky light)";
            default -> "off";
        };
        source.sendSuccess(() -> Component.literal("DH LOD debug view " + description), false);
        return 1;
    }

    /**
     * Enables or disables colored lighting on Distant Horizons LODs, persisting the choice to the
     * config. Enabling also relights the current area so the color memory has data to start from.
     */
    private static int setDhLodColor(CommandSourceStack source, boolean enable) {
        String message;
        if (enable && !ColoredLightEngine.isEnabled()) {
            // The clientTick reconcile will bind the override once '/cl on' turns the engine on.
            message = "Colored LOD lighting saved; it will turn on together with colored lighting (/cl on)";
        } else {
            message = DhCompat.setOverrideEnabled(enable);
        }
        if (DhCompat.isLoaded()) {
            ColorfulLightingConfig.DH_LOD_COLOR.set(enable);
            ColorfulLightingConfig.save();
        }
        if (enable && DhCompat.isOverrideEnabled()) {
            // Repropagate the loaded area so its colours get captured into the LOD color memory
            ColoredLightEngine.resetAll();
            if (Minecraft.getInstance().levelRenderer != null) {
                Minecraft.getInstance().levelRenderer.allChanged();
            }
        }
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    /**
     * The flywheel colored-light transport can't switch live: GlCompat.MAX_GLSL_VERSION is read
     * once at class init and baked into every compiled flywheel shader as its #version, and the
     * Java side must match the shaders exactly. So the command writes the config and asks for a
     * restart instead.
     */
    private static int setFlywheelTextureMode(CommandSourceStack source, boolean forceTexture) {
        ColorfulLightingConfig.FLYWHEEL_FORCE_TEXTURE_MODE.set(forceTexture);
        ColorfulLightingConfig.save();
        if (forceTexture) {
            source.sendSuccess(() -> Component.literal("Flywheel colored light set to buffer-texture mode (GLSL capped at 410). Restart the game to apply, then use '/flywheel backend instancing' — the indirect backend needs GLSL 460 and cannot run while capped."), false);
        } else {
            source.sendSuccess(() -> Component.literal("Flywheel colored light set to auto (SSBO when the GPU supports GLSL 430). Restart the game to apply."), false);
        }
        return 1;
    }
}
