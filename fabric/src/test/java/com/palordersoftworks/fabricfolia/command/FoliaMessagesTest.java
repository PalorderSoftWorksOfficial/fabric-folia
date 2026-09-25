/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.command;

import net.minecraft.network.chat.MutableComponent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Formatting + metadata: MiniMessage parses into vanilla component trees
 * (colors, decorations, nesting survive the conversion), version rows come
 * from the loader (no fabricated values), and help topics are
 * self-contained.
 */
class FoliaMessagesTest {

	@Test
	void simpleColorParsesIntoVanillaComponent() {
		MutableComponent component = FoliaMessages.toMinecraft("<gold>hello</gold>");
		assertEquals("hello", component.getString());
		assertEquals("gold", component.getStyle().getColor().toString());
	}

	@Test
	void nestedTagsProduceNestedComponentsWithStyles() {
		MutableComponent component = FoliaMessages.toMinecraft(
				"<red>bad</red> <gray>plain <bold>bold</bold></gray>");
		assertEquals("bad plain bold", component.getString());
		assertTrue(component.getSiblings().size() >= 2);
		MutableComponent bold = findBold(component);
		assertNotNull(bold, "the bold segment must keep its decoration");
	}

	@Test
	void statusHelpersCarrySemanticColors() {
		assertEquals("green", FoliaMessages.ok("fine").getStyle().getColor().toString());
		assertEquals("yellow", FoliaMessages.warn("hmm").getStyle().getColor().toString());
		assertEquals("red", FoliaMessages.error("bad").getStyle().getColor().toString());
	}

	@Test
	void consoleRenderingStripsTags() {
		String console = FoliaMessages.toConsoleString("<green>✔ OK</green>");
		assertEquals("✔ OK", console);
		assertFalse(console.contains("<"));
	}

	@Test
	void versionRowsArePresentAndNeverBlank() {
		List<FoliaVersion.Row> rows = FoliaVersion.rows();
		assertEquals(7, rows.size());
		for (FoliaVersion.Row row : rows) {
			assertNotNull(row.value());
			assertFalse(row.value().isBlank(), row.label() + " must never be blank");
		}
		assertTrue(rows.stream().anyMatch(r -> r.label().equals("Minecraft")));
		assertTrue(rows.stream().anyMatch(r -> r.label().equals("FabricFolia")));
		assertTrue(rows.stream().anyMatch(r -> r.label().equals("Java")));
	}

	@Test
	void helpOverviewListsEveryCommand() {
		List<String> overview = FoliaHelp.overview();
		String joined = String.join("\n", overview);
		for (String command : List.of("version", "regions", "workers", "scheduler",
				"patches", "health", "threads", "help")) {
			assertTrue(joined.contains("/folia " + command), "missing /folia " + command);
		}
	}

	@Test
	void helpTopicsExistAndAreSubstantial() {
		for (String topic : List.of("regions", "workers", "scheduler", "patches",
				"health", "threads", "configuration", "thread checks", "regionization")) {
			List<String> content = FoliaHelp.topic(topic);
			assertNotNull(content, "missing topic: " + topic);
			assertTrue(content.size() >= 3, "topic " + topic + " is too thin");
		}
		assertTrue(FoliaHelp.topic("NO-SUCH") == null, "unknown topic must return null");
	}

	private static MutableComponent findBold(MutableComponent root) {
		if (root.getStyle().isBold()) {
			return root;
		}
		for (var sibling : root.getSiblings()) {
			if (sibling instanceof MutableComponent mutable && findBold(mutable) != null) {
				return mutable;
			}
			if (sibling.getStyle().isBold()) {
				return MutableComponent.create(sibling.getContents()).setStyle(sibling.getStyle());
			}
		}
		return null;
	}
}
