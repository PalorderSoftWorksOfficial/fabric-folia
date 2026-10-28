package com.palordersoftworks.fabricfolia.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The /folia permission contract: OP by default, one LuckPerms-friendly
 * umbrella ({@code folia.command}) for the whole diagnostics tier, plus
 * per-sub nodes and the legacy atoms. A renamed node silently breaks every
 * permission setup, so the exact strings are pinned here.
 */
class FoliaCommandPermissionTest {

	@Test
	void nodeStringsArePinned() {
		assertEquals("folia.command", FoliaCommand.umbrellaNode());
		assertEquals("folia.command.regions", FoliaCommand.subNode("regions"));
		assertEquals("folia.command.admin", FoliaCommand.subNode("admin"));
		assertEquals("fabricfolia.command.folia.regions", FoliaCommand.atomNode("regions"));
	}

	@Test
	void umbrellaGrantsEveryLeaf() {
		for (String leaf : new String[] {"regions", "workers", "scheduler", "patches",
				"health", "threads", "admin"}) {
			assertTrue(FoliaCommand.grants(false, false, true, false),
					"umbrella must grant " + leaf);
		}
	}

	@Test
	void eachGrantPathIndependentlySufficient() {
		assertTrue(FoliaCommand.grants(true, false, false, false), "owner must grant");
		assertTrue(FoliaCommand.grants(false, true, false, false), "legacy atom must grant");
		assertTrue(FoliaCommand.grants(false, false, true, false), "umbrella must grant");
		assertTrue(FoliaCommand.grants(false, false, false, true), "per-sub node must grant");
	}

	@Test
	void noGrantMeansDenied() {
		assertFalse(FoliaCommand.grants(false, false, false, false),
				"a non-OP player with no nodes must be denied");
	}
}
