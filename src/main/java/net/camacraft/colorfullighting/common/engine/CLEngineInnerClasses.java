package net.camacraft.colorfullighting.common.engine;

import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

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
	
	public static class BlockUpdates {
		public final Map<BlockPos, LightUpdateRequest> updates = new HashMap<>();
		
		public void add(LightUpdateRequest increaseRequest) {
			CLEngineInnerClasses.LightUpdateRequest old = updates.put(increaseRequest.blockPos, increaseRequest);
			if (old != null) {
				increaseRequest.force |= old.force;
				increaseRequest.checkSource |= old.checkSource;
				increaseRequest.repropagate |= old.repropagate;
			}
		}
		
		public int size() {
			return updates.size();
		}
		
		public void clear() {
			updates.clear();
		}
		
		public boolean isEmpty() {
			return updates.isEmpty();
		}
		
		public Set<BlockPos> keySet() {
			return updates.keySet();
		}
		
		public LightUpdateRequest get(BlockPos pos) {
			return updates.get(pos);
		}
		
		public LightUpdateRequest remove(BlockPos pos) {
			return updates.remove(pos);
		}
		
		public Collection<LightUpdateRequest> valueSet() {
			return updates.values();
		}
	}
	
	public static class LightUpdateRequest {
		public final BlockPos blockPos;
		public ColorRGB4 lightColor;
		public boolean force;
		public boolean checkSource;
		public boolean repropagate;
		
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
