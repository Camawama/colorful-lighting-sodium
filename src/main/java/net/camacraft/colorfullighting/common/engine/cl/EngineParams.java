package net.camacraft.colorfullighting.common.engine.cl;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.renderer.culling.Frustum;

import java.util.concurrent.atomic.AtomicInteger;

public class EngineParams {
	public final LongOpenHashSet dirtySections;
	public final Object storageLock;
	public final Frustum frustum;
	public final AtomicInteger structureVersion;
	
	public EngineParams(LongOpenHashSet dirtySections, Object storageLock, Frustum frustum, AtomicInteger structureVersion) {
		this.dirtySections = dirtySections;
		this.storageLock = storageLock;
		this.frustum = frustum;
		this.structureVersion = structureVersion;
	}
}
