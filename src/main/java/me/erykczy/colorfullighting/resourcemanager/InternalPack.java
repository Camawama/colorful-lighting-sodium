package me.erykczy.colorfullighting.resourcemanager;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.Pack;
import net.minecraftforge.forgespi.language.IModFileInfo;

import java.nio.file.Path;

public class InternalPack {
	public final String addID;
	public final Pack.Position position;
	public final ResourceLocation id;
	public final Component displayName;
	public final IModFileInfo info;
	public final Path resourcePath;
	public final boolean display;
	
	public InternalPack(ResourceLocation id, Component displayName, IModFileInfo info, Path resourcePath, boolean display) {
		this.id = id;
		this.displayName = displayName;
		this.info = info;
		this.resourcePath = resourcePath;
		this.display = display;
		
		this.addID = id.getNamespace() + ":add_pack/" + id.getPath();
		
		this.position = Pack.Position.TOP;
	}
	
	public InternalPack(ResourceLocation id, Component displayName, IModFileInfo info, Path resourcePath, boolean display, Pack.Position position) {
		this.id = id;
		this.displayName = displayName;
		this.info = info;
		this.resourcePath = resourcePath;
		this.display = display;
		
		this.addID = id.getNamespace() + ":add_pack/" + id.getPath();
		
		this.position = position;
	}
}
