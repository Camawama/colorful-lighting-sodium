package net.camacraft.colorfullighting.common.engine;

import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Queue;

public class CLEngineInnerClasses {
	public static class BlockRequests {
		public BlockPos blockPos;
		// ArrayDeque, not LinkedList: propagation enqueues millions of requests per minute and
		// LinkedList allocates a Node per element (visible in the 2026-08-06 JFR captures)
		public Queue<LightUpdateRequest> increaseRequests = new ArrayDeque<>();
		
		public BlockRequests(BlockPos blockPos) {
			this.blockPos = blockPos;
		}
	}
	
	public static class LightUpdateRequest {
		public final BlockPos blockPos;
		public ColorRGB4 lightColor;
		public final boolean force;
		public final boolean checkSource;
		public final boolean repropagate;
		
		public LightUpdateRequest(BlockPos blockPos, ColorRGB4 lightColor, boolean force) {
			this(blockPos, lightColor, force, false, false);
		}
		
		public LightUpdateRequest(BlockPos blockPos, ColorRGB4 lightColor, boolean force, boolean checkSource) {
			this(blockPos, lightColor, force, checkSource, false);
		}
		
		public LightUpdateRequest(BlockPos blockPos, ColorRGB4 lightColor, boolean force, boolean checkSource, boolean repropagate) {
			this.blockPos = blockPos;
			this.lightColor = lightColor;
			this.force = force;
			this.checkSource = checkSource;
			this.repropagate = repropagate;
		}
	}
	
	public record DelayedChunkUpdate(ChunkPos chunkPos, long executeTime) {}
}
