package me.erykczy.colorfullighting.api;

/**
 * API interface for other mods to implement if they do custom rendering for a Client Level
 * If you are implementing this interface, CL will utilize these methods as a fallback incase you're lacking otherwise necessary features
 *
 * For example, let's say you have a world which can be rendered, but doesn't have a dedicated level renderer
 * In this case, you still need to know when a section gets marked as "dirty", but CL has no good method of supplying you with that information
 * Implementing the {@link CLClientLevel#colorfullighting$setSectionDirty(int, int, int)} gives CL a good method of notifying your custom renderer that a section was marked dirty
 */
public interface CLClientLevel {
	void colorfullighting$setSectionDirty(int x, int y, int z);
}
