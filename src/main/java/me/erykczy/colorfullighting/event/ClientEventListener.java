package me.erykczy.colorfullighting.event;

import com.mojang.brigadier.CommandDispatcher;
import me.erykczy.colorfullighting.ColorfulLighting;
import me.erykczy.colorfullighting.common.BeaconEffectSync;
import me.erykczy.colorfullighting.common.BlockEntityNbtCache;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.ViewArea;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelRendererAccessor;
import me.erykczy.colorfullighting.compat.dynamiclights.DynamicLightsCompat;
import me.erykczy.colorfullighting.compat.oculus.cmd.PackArgumentType;
import me.erykczy.colorfullighting.compat.oculus.cmd.ShaderPackName;
import me.erykczy.colorfullighting.compat.valkyrienskies.VsCompat;
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
		
	    if (ColorfulLighting.clientAccessor == null) return;
	    var player = ColorfulLighting.clientAccessor.getPlayer();
	    if (player == null) return;
	    
	    // Snapshot dynamic light sources (SodiumDynamicLights) for this tick
	    if (event.level instanceof ClientLevel clientLevel) {
		    DynamicLightsCompat.clientTick(clientLevel);
	    }
		
	    ChunkPos pos = player.getChunkPos();
	    int renderDistance = ColorfulLighting.clientAccessor.getRenderDistance();
	    ViewArea viewArea = new ViewArea(
			    pos.x - renderDistance,
			    pos.z - renderDistance,
			    pos.x + renderDistance,
			    pos.z + renderDistance
	    );
	    
	    LevelAttachments attachments = (LevelAttachments) event.level;
		ColoredLightEngine engine = attachments.colorfullighting$getEngine();
		engine.updateViewArea(viewArea);
		
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
					                        me.erykczy.colorfullighting.compat.oculus.ShaderpackAutoPatcher.runAsync(pck.getName(), message -> {
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
                        // Diagnostic and power-user commands live under one literal so the
                        // top-level autocomplete stays a short list: on, off, purge, debug.
                        .then(Commands.literal("debug")
                                .then(Commands.literal("patchshaders")
                                        .executes(context -> {
                                            context.getSource().sendSuccess(() -> Component.literal("Patching shaderpacks for Colorful Lighting..."), false);
                                            me.erykczy.colorfullighting.compat.oculus.ShaderpackAutoPatcher.runAsync(message -> {
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
                                                    me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat.describeMode()), false);
                                            boolean forced = me.erykczy.colorfullighting.common.ColorfulLightingConfig.flywheelForceTextureMode();
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "Configured: " + (forced ? "texture (forced for testing)" : "auto (SSBO when the GPU supports GLSL 430)")
                                                            + " — change with /cl debug flywheel texture|auto"), false);
                                            if (forced
                                                    && me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat.isAvailable()
                                                    && !me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat.isTextureFallback()) {
                                                context.getSource().sendSuccess(() -> Component.literal(
                                                        "WARNING: texture mode is configured but SSBO mode is active — the setting was read after flywheel initialized. Add -Dcolorfullighting.flywheelForceTextureMode=true to your JVM arguments instead."), false);
                                            }
                                            return 1;
                                        })
                                        .then(Commands.literal("report")
                                                .executes(context -> {
                                                    var compat = me.erykczy.colorfullighting.compat.flywheel.FlywheelCompat.getInstance();
                                                    String report = compat == null
                                                            ? "Flywheel colored light is inactive"
                                                            : compat.flywheelColoredLightStorage.debugReport();
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

    /**
     * The flywheel colored-light transport can't switch live: GlCompat.MAX_GLSL_VERSION is read
     * once at class init and baked into every compiled flywheel shader as its #version, and the
     * Java side must match the shaders exactly. So the command writes the config and asks for a
     * restart instead.
     */
    private static int setFlywheelTextureMode(CommandSourceStack source, boolean forceTexture) {
        me.erykczy.colorfullighting.common.ColorfulLightingConfig.FLYWHEEL_FORCE_TEXTURE_MODE.set(forceTexture);
        me.erykczy.colorfullighting.common.ColorfulLightingConfig.save();
        if (forceTexture) {
            source.sendSuccess(() -> Component.literal("Flywheel colored light set to buffer-texture mode (GLSL capped at 410). Restart the game to apply, then use '/flywheel backend instancing' — the indirect backend needs GLSL 460 and cannot run while capped."), false);
        } else {
            source.sendSuccess(() -> Component.literal("Flywheel colored light set to auto (SSBO when the GPU supports GLSL 430). Restart the game to apply."), false);
        }
        return 1;
    }
}
