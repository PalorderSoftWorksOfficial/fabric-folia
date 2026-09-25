/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * Converts Adventure components (MiniMessage output) into vanilla 26.2
 * {@link MutableComponent} trees. Implemented directly over the component
 * tree (text children + style) — no adventure-platform dependency; MC 26.2
 * ships no Adventure, so the conversion is ours. Non-text children render as
 * their plain text; the command layer only produces text components.
 */
public final class ComponentConverter {

	private ComponentConverter() {
	}

	public static MutableComponent toMinecraft(Component component) {
		MutableComponent root = convertOne(component);
		for (Component child : component.children()) {
			root.append(toMinecraft(child));
		}
		return root;
	}

	private static MutableComponent convertOne(Component component) {
		String content = component instanceof TextComponent text ? text.content() : "";
		MutableComponent result = net.minecraft.network.chat.Component.literal(content);
		result.setStyle(convertStyle(component.style()));
		return result;
	}

	private static Style convertStyle(net.kyori.adventure.text.format.Style source) {
		Style target = Style.EMPTY;
		if (source.color() != null) {
			int rgb = source.color().value();
			ChatFormatting legacy = legacyFor(rgb);
			target = legacy != null ? target.withColor(legacy) : target.withColor(rgb);
		}
		if (source.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE) {
			target = target.withBold(true);
		}
		if (source.decoration(TextDecoration.ITALIC) == TextDecoration.State.TRUE) {
			target = target.withItalic(true);
		}
		if (source.decoration(TextDecoration.UNDERLINED) == TextDecoration.State.TRUE) {
			target = target.withUnderlined(true);
		}
		if (source.decoration(TextDecoration.STRIKETHROUGH) == TextDecoration.State.TRUE) {
			target = target.withStrikethrough(true);
		}
		if (source.decoration(TextDecoration.OBFUSCATED) == TextDecoration.State.TRUE) {
			target = target.withObfuscated(true);
		}
		return target;
	}

	private static ChatFormatting legacyFor(int rgb) {
		return switch (rgb) {
			case 0x000000 -> ChatFormatting.BLACK;
			case 0x0000AA -> ChatFormatting.DARK_BLUE;
			case 0x00AA00 -> ChatFormatting.DARK_GREEN;
			case 0x00AAAA -> ChatFormatting.DARK_AQUA;
			case 0xAA0000 -> ChatFormatting.DARK_RED;
			case 0xAA00AA -> ChatFormatting.DARK_PURPLE;
			case 0xFFAA00 -> ChatFormatting.GOLD;
			case 0xAAAAAA -> ChatFormatting.GRAY;
			case 0x555555 -> ChatFormatting.DARK_GRAY;
			case 0x5555FF -> ChatFormatting.BLUE;
			case 0x55FF55 -> ChatFormatting.GREEN;
			case 0x55FFFF -> ChatFormatting.AQUA;
			case 0xFF5555 -> ChatFormatting.RED;
			case 0xFF55FF -> ChatFormatting.LIGHT_PURPLE;
			case 0xFFFF55 -> ChatFormatting.YELLOW;
			case 0xFFFFFF -> ChatFormatting.WHITE;
			default -> null;
		};
	}
}
