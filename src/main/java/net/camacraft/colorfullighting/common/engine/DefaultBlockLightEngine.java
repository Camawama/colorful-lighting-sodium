package net.camacraft.colorfullighting.common.engine;

import net.camacraft.colorfullighting.common.*;
import net.camacraft.colorfullighting.common.accessors.LevelAccessor;
import net.camacraft.colorfullighting.common.util.ColorRGB4;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

public class DefaultBlockLightEngine extends ColoredBlockLightEngine {
	boolean forLight;
	ColoredLightEngine engine;
	
	public DefaultBlockLightEngine(boolean forLight, ColoredLightEngine engine) {
		this.forLight = forLight;
		this.engine = engine;
	}
	
	// TODO: these should be protected
	// those first added will be executed first (this order is required by decrease propagation algorithm)
	public final ConcurrentLinkedQueue<ColoredLightEngine.LightUpdateRequest> blockUpdateDecreaseRequests = new ConcurrentLinkedQueue<>();
	// those nearest to the player will be executed first
	public final ConcurrentLinkedQueue<ColoredLightEngine.BlockRequests> blockUpdateIncreaseRequests = new ConcurrentLinkedQueue<>();
	// light/darkness storage
	public final ColoredLightStorage storage = new ColoredLightStorage();
	
	@Override
	public String describeQueue() {
		return (forLight ? "block" : "dark") + " updates queued: light +" + blockUpdateIncreaseRequests.size() + " -" + blockUpdateDecreaseRequests.size();
	}
	
	@Override
	public void remove(ViewArea newArea) {
		blockUpdateIncreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos) && !engine.extraRegionsContainBlockInner(blockUpdate.blockPos));
		blockUpdateDecreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos) && !engine.extraRegionsContainBlockInner(blockUpdate.blockPos));
	}
	
	@Override
	public void removeAlt(ViewArea newArea) {
		blockUpdateIncreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos) && !engine.isBlockTrackedInner(blockUpdate.blockPos));
		blockUpdateDecreaseRequests.removeIf(blockUpdate -> !newArea.containsBlockInner(blockUpdate.blockPos) && !engine.isBlockTrackedInner(blockUpdate.blockPos));
	}
	
	@Override
	public void handleBlockUpdate(LevelAccessor level, ColoredLightEngine.BlockRequests requests, BlockPos blockPos) {
		ColorRGB4 lightColor = storage.getEntry(blockPos);
		if (lightColor == null) lightColor = ColorRGB4.fromRGB4(0,0,0);
		
		if(lightColor.red4 == 0 && lightColor.green4 == 0 && lightColor.blue4 == 0)
			requestLightPullIn(requests.increaseRequests, blockPos);  // block probably destroyed/replaced with transparent, light pull in might be needed
		else
			blockUpdateDecreaseRequests.add(new ColoredLightEngine.LightUpdateRequest(blockPos, lightColor, false)); // block probably placed/replaced with non-transparent, light might need to be decreased
		
		// propagate light if new blockState emits light (single lookup for both brightness and color)
		BlockState blockState = level.getBlockState(blockPos);
		
		int emission = forLight ? Config.getEmissionBrightness(level, blockPos, blockState) : Config.getAbsorption(level, blockPos, blockState);
		
		if (blockState != null && emission > 0) {
			ColorRGB4 color = forLight ? Config.getColorEmission(level, blockPos, blockState) : Config.getAbsorptionColor(level, blockPos, blockState);
			requests.increaseRequests.add(new ColoredLightEngine.LightUpdateRequest(blockPos, color, false, true, false));
		}
		
		if (!requests.increaseRequests.isEmpty()) {
			blockUpdateIncreaseRequests.add(requests);
		}
	}
	
	private void requestLightPullIn(Queue<ColoredLightEngine.LightUpdateRequest> requests, BlockPos blockPos) {
		for(var direction : Direction.values()) {
			BlockPos neighbourPos = blockPos.relative(direction);
			ColorRGB4 neighbourLight = storage.getEntry(neighbourPos);
			if(neighbourLight == null) continue;
			
			if(neighbourLight.red4 == 0 && neighbourLight.green4 == 0 && neighbourLight.blue4 == 0) continue;
			requests.add(new ColoredLightEngine.LightUpdateRequest(neighbourPos, null, true, false, true));
		}
	}
	
	@Override
	public ColoredLightSection getSection(long sectionPos) {
		return storage.getSection(sectionPos);
	}
	
	@Override
	public void removeSection(long sectionPos) {
		storage.removeSection(sectionPos);
	}
	
	@Override
	public void addSection(long pos) {
		storage.addSection(pos);
	}
	
	@Override
	public int sectionCount() {
		return storage.sectionCount();
	}
	
	@Override
	public void forEachPopulatedSection(LongConsumer action) {
		storage.forEachPopulatedSection(action);
	}
	
	@Override
	public void clear() {
		storage.clear();
		blockUpdateIncreaseRequests.clear();
		blockUpdateDecreaseRequests.clear();
	}
	
	@Override
	public boolean hasWork() {
		return !blockUpdateDecreaseRequests.isEmpty() || !blockUpdateIncreaseRequests.isEmpty();
	}
	
	@Override
	public ColorRGB4 getColor(BlockPos blockPos) {
		return storage.getEntry(blockPos);
	}
	
	@Override
	public int getValue(LevelAccessor level, BlockPos blockPos, BlockState blockState) {
		return forLight ? Config.getEmissionBrightness(level, blockPos, blockState) : Config.getAbsorption(level, blockPos, blockState);
	}
	
	@Override
	public ColorRGB4 getColor(LevelAccessor level, BlockPos blockPos, BlockState blockState) {
		return forLight ? Config.getColorEmission(level, blockPos, blockState) : Config.getAbsorptionColor(level, blockPos, blockState);
	}
	
	// CODE REGION: PROPAGATION LOGIC
	public ConcurrentHashMap<BlockPos, ColorRGB4> changesInProgress = new ConcurrentHashMap<>();
	public final ConcurrentHashMap<BlockPos, ColorRGB4> changesReady = new ConcurrentHashMap<>();
	public final Lock changesReadyLock = new ReentrantLock();
}
