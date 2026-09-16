/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Frame;
import java.awt.Image;
import java.awt.GraphicsEnvironment;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real icon behavior: the packaged resource, the load path,
 * and {@link ServerGuiIcon#applyTo} against a real {@link JFrame} where the
 * environment allows — the same code the server GUI window runs.
 */
class ServerGuiIconTest {

	@Test
	void logoResourceIsPackagedAndDecodable() throws Exception {
		// Exact packaging contract: jar root, resource name fixed by the mixin.
		assertNotNull(ServerGuiIcon.class.getClassLoader().getResource(ServerGuiIcon.RESOURCE),
				"logo.png must be packaged at the jar root for the GUI icon");
		try (java.io.InputStream in = ServerGuiIcon.class.getClassLoader()
				.getResource(ServerGuiIcon.RESOURCE).openStream()) {
			Image decoded = javax.imageio.ImageIO.read(in);
			assertNotNull(decoded, "logo.png must be a decodable PNG");
			assertTrue(decoded.getWidth(null) > 0 && decoded.getHeight(null) > 0);
		}
	}

	@Test
	void loadFrameIconFollowsEnvironment() {
		if (GraphicsEnvironment.isHeadless()) {
			assertNull(ServerGuiIcon.loadFrameIcon());
		} else {
			Image icon = ServerGuiIcon.loadFrameIcon();
			assertNotNull(icon, "non-headless environment must load logo.png");
			assertTrue(icon.getWidth(null) > 0 && icon.getHeight(null) > 0);
		}
	}

	@Test
	void applyToSetsRealFrameIcon() throws Exception {
		if (GraphicsEnvironment.isHeadless()) {
			return; // covered by loadFrameIconFollowsEnvironment's null path
		}
		SwingUtilities.invokeAndWait(() -> {
			JFrame frame = new JFrame("fabricfolia-icon-test");
			try {
				assertTrue(ServerGuiIcon.applyTo(frame),
						"applyTo must apply the packaged logo to a real frame");
				Image icon = frame.getIconImage();
				assertNotNull(icon, "frame icon must be set after applyTo");
				assertTrue(ServerGuiIcon.awaitLoaded(icon, 5000),
						"frame icon must fully decode well within the 5s budget");
				assertEquals(512, icon.getWidth(null), "icon keeps the source image dimensions");
			} finally {
				frame.dispose();
			}
		});
	}

	@Test
	void applyToRejectsNullFrameWithoutThrowing() {
		// Contract: never throws, even for a null frame (defensive; the mixin
		// can only pass the vanilla-created frame, but the helper is public).
		assertFalse(ServerGuiIcon.applyTo((Frame) null));
	}
}
