package me.erykczy.colorfullighting.mixin.compat.sodium.sodium;

import me.erykczy.colorfullighting.accessors.BlockStateWrapper;
import me.erykczy.colorfullighting.common.ColoredLightEngine;
import me.erykczy.colorfullighting.common.Config;
import me.erykczy.colorfullighting.common.accessors.BlockStateAccessor;
import me.erykczy.colorfullighting.common.accessors.mixin.LevelAttachments;
import me.erykczy.colorfullighting.common.util.ColorRGB4;
import me.erykczy.colorfullighting.common.util.ColorRGB8;
import me.erykczy.colorfullighting.compat.sodium.SodiumPackedLightData;
import me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess;
import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.light.flat.FlatLightPipeline;
import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFlags;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Arrays;

@Mixin(value = FlatLightPipeline.class, remap = false)
public class FlatLightPipelineMixin {
	@Shadow
	@Final
	private LightDataAccess lightCache;
	
	/**
	 * @author Erykczy
	 * @reason Inject colored lighting logic
	 */
	@Overwrite
	private int getOffsetLightmap(BlockPos pos, Direction face) {
		if (!ColoredLightEngine.isEnabled()) {
			// Replicate vanilla/Sodium logic
			int word = this.lightCache.get(pos);
			if (LightDataAccess.unpackEM(word)) {
				return 0xF000F0;
			}
			int adjWord = this.lightCache.get(pos, face);
			return LightDataAccess.getLightmap(adjWord);
		}
		
		// 1. Always sample the light color for the offset position first.
		// This ensures light propagation is never skipped or blocked.
		
		// I'm leaving that comment there incase something breaks
		// But I do not see any reason why that would be true
		// so I moved it to only sample the light color IF the light color is going to be used
		BlockAndTintGetter level = this.lightCache.getWorld();
		BlockPos offsetPos = pos.relative(face);
		
		int adjWord = this.lightCache.get(pos, face);
		int skyLight = LightDataAccess.unpackSL(adjWord);
		
		int word = this.lightCache.get(pos);
		
		// 2. Check if the block has the emissive flag
		if (LightDataAccess.unpackEM(word)) {
			BlockState state = level.getBlockState(pos);
			
			// 3. Only override the light if the block ACTUALLY emits configured light
			var emission = Config.getLightColor(state);
			if (!emission.equals(Config.defaultColor)) {
				return SodiumPackedLightData.packData(skyLight, ColorRGB8.fromRGB4(emission));
			}
		}
		
		// 4. Fallback to normal lighting behavior with the properly sampled ambient light color
		int sampledColor = ((LevelAttachments) level).colorfullighting$getEngine().sampleLightColorInt(offsetPos);
		return SodiumPackedLightData.packDataFromRGB4(skyLight, sampledColor);
	}
	
	/**
	 * @author Erykczy
	 * @reason Ensure colored lighting is used even for non-offset faces (plants, etc.)
	 */
	@Overwrite
	public void calculate(ModelQuadView quad, BlockPos pos, QuadLightData out, Direction cullFace, Direction lightFace, boolean shade) {
		if (!ColoredLightEngine.isEnabled()) {
			// Replicate vanilla/Sodium logic
			int lightmap = 0;
			if (cullFace != null) {
				lightmap = getOffsetLightmap(pos, cullFace);
			} else {
				int flags = quad.getFlags();
				if ((flags & ModelQuadFlags.IS_ALIGNED) != 0 || ((flags & ModelQuadFlags.IS_PARALLEL) != 0 && LightDataAccess.unpackFC(this.lightCache.get(pos)))) {
					lightmap = getOffsetLightmap(pos, lightFace);
				} else {
					lightmap = LightDataAccess.getLightmap(this.lightCache.get(pos));
				}
			}
			Arrays.fill(out.lm, lightmap);
			Arrays.fill(out.br, this.lightCache.getWorld().getShade(lightFace, shade));
			return;
		}
		
		int lightmap = 0;
		
		if (cullFace != null) {
			lightmap = getOffsetLightmap(pos, cullFace);
		} else {
			int flags = quad.getFlags();
			if ((flags & ModelQuadFlags.IS_ALIGNED) != 0 || ((flags & ModelQuadFlags.IS_PARALLEL) != 0 && LightDataAccess.unpackFC(this.lightCache.get(pos)))) {
				lightmap = getOffsetLightmap(pos, lightFace);
			} else {
				int word = this.lightCache.get(pos);
				
				BlockAndTintGetter level = this.lightCache.getWorld();
				// Always sample light at pos
				int skyLight = LightDataAccess.unpackSL(word);
				
				boolean overridden = false;
				
				if (LightDataAccess.unpackEM(word)) {
					BlockState state = level.getBlockState(pos);
					
					var emission = Config.getLightColor(state);
					if (!emission.equals(Config.defaultColor)) {
						lightmap = SodiumPackedLightData.packData(skyLight, ColorRGB8.fromRGB4(emission));
						overridden = true;
					}
				}
				
				if (!overridden) {
					int sampledColor = ((LevelAttachments) level).colorfullighting$getEngine().sampleLightColorInt(pos);
					lightmap = SodiumPackedLightData.packDataFromRGB4(skyLight, sampledColor);
				}
			}
		}
		
		Arrays.fill(out.lm, lightmap);
		Arrays.fill(out.br, this.lightCache.getWorld().getShade(lightFace, shade));
	}
}
