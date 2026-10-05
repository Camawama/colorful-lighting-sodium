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
		public BlockUpdates increaseRequests = new BlockUpdates();
		
		public BlockRequests(BlockPos blockPos) {
			this.blockPos = blockPos;
		}
	}
	
	public static class BlockUpdates {
		BlockPos latestPoll;
		public final ArrayDeque<BlockPos> positions = new ArrayDeque<>();
		public final Map<BlockPos, LightUpdateRequest> updates = new HashMap<>();
		
		public void add(LightUpdateRequest increaseRequest) {
			add(increaseRequest, true);
		}
		
		public void add(LightUpdateRequest increaseRequest, boolean max) {
			CLEngineInnerClasses.LightUpdateRequest old = updates.put(increaseRequest.blockPos, increaseRequest);
			if (old != null) {
				if (max) {
					if (increaseRequest.lightColor == null) {
						increaseRequest.lightColor = old.lightColor;
					} else if (old.lightColor != null) {
						increaseRequest.lightColor = ColorRGB4.max(
								increaseRequest.lightColor, old.lightColor
						);
					}
				} else {
					if (increaseRequest.lightColor != null && old.lightColor != null) {
						increaseRequest.lightColor = ColorRGB4.min(
								increaseRequest.lightColor, old.lightColor
						);
					}
				}
				increaseRequest.force |= old.force;
				increaseRequest.checkSource |= old.checkSource;
				increaseRequest.repropagate |= old.repropagate;
			} else {
				positions.add(increaseRequest.blockPos);
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
			positions.remove(pos);
			return updates.remove(pos);
		}
		
		public Collection<LightUpdateRequest> valueSet() {
			return updates.values();
		}
		
		public LightUpdateRequest poll() {
			return updates.remove(latestPoll = positions.poll());
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
