package me.erykczy.colorfullighting.api;

/**
 * API interface for other mods to implement if they do custom rendering for a Client Level
 */
public interface CLClientLevel {
	void setSectionDirty(int x, int y, int z);
}
