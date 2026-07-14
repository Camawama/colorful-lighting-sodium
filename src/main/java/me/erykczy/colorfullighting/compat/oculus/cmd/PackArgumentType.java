package me.erykczy.colorfullighting.compat.oculus.cmd;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.irisshaders.iris.Iris;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

public class PackArgumentType implements ArgumentType<ShaderPackName> {
	public static final SimpleCommandExceptionType ERROR_NOT_COMPLETE = new SimpleCommandExceptionType(Component.translatable("colorfullighting.argument.shader.incomplete"));
	
	@Override
	public ShaderPackName parse(StringReader reader) throws CommandSyntaxException {
		if (reader.getRemainingLength() == 0) return null;
		
		String rem;
		
		try {
			reader.expect('"');
			rem = "\"";
			while (reader.peek() != '\"') {
				rem += reader.peek();
				reader.skip();
			}
			rem += "\"";
			reader.skip();
		} catch (Throwable ignored) {
			rem = "";
			while (true) {
				if (reader.getRemainingLength() == 0)
					break;
				
				char c = reader.peek();
				
				if (reader.isAllowedInUnquotedString(c)) {
					rem += c;
					reader.skip();
				} else {
					break;
				}
			}
		}
		
		try {
			for (String s1 : Iris.getShaderpacksDirectoryManager().enumerate()) {
				if (s1.equals(rem)) {
					return new ShaderPackName(s1);
				}
				
				String s2 = "\"" + s1 + "\"";
				
				if (s2.equals(rem)) {
					return new ShaderPackName(s1);
				}
			}
		} catch (Throwable ignored) {
		}
		
		throw ERROR_NOT_COMPLETE.createWithContext(reader);
	}
	
	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		String s = builder.getRemaining();
		
		try {
			for (String s1 : Iris.getShaderpacksDirectoryManager().enumerate()) {
				if (s1.contains(" "))
					s1 = "\"" + s1 + "\"";
				
				if (s1.startsWith(s)) {
					builder.suggest(s1);
				}
			}
		} catch (Throwable ignored) {
		}

//		return Suggestions.empty();
		return builder.buildFuture();
	}
	
	@Override
	public Collection<String> getExamples() {
		return Collections.emptyList();
	}
}
