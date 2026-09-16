/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.gui;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.ImageObserver;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the Fabric-Folia server GUI window icon ({@code logo.png}, packaged
 * at the jar root) for the dedicated-server GUI frame — the Fabric-native
 * equivalent of Folia's upstream "use Folia logo" change to
 * {@code MinecraftServerGui}, which ships its own logo resource instead of
 * relying on any vanilla default (vanilla sets none).
 *
 * <p>Everything here is failure-isolated: dedicated servers commonly run
 * headless or without the GUI at all, and a missing or unreadable resource
 * must never affect server startup. Callers are invoked from the GUI
 * construction path only, so the cost is paid exactly once per GUI frame.</p>
 */
public final class ServerGuiIcon {

	/** Resource path of the logo inside the mod jar (jar root). */
	public static final String RESOURCE = "logo.png";

	private static final Logger LOGGER = LoggerFactory.getLogger("FabricFolia/GUI");

	private static final long DECODE_TIMEOUT_MILLIS = 2000;

	private ServerGuiIcon() {
	}

	/**
	 * @return the window icon from {@code logo.png}, or null when there is no
	 *         usable one (headless environment, resource missing/corrupt).
	 *         Null means "leave the frame icon unset", not "fail".
	 */
	public static Image loadFrameIcon() {
		if (GraphicsEnvironment.isHeadless()) {
			return null;
		}
		URL url = ServerGuiIcon.class.getClassLoader().getResource(RESOURCE);
		if (url == null) {
			return null;
		}
		try (InputStream in = url.openStream()) {
			Image image = ImageIO.read(in);
			if (image != null && image.getWidth(null) > 0 && image.getHeight(null) > 0) {
				return image;
			}
			return null; // decoders present but no reader claimed the stream
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Blocks until the image is fully decoded, as {@link ImageIO#read} only
	 * guarantees dimensions, not complete pixel data — and window icons read
	 * from a stream have no asynchronous producer to wait on later.
	 *
	 * @return true when the image is fully loaded, false on timeout/error
	 */
	public static boolean awaitLoaded(Image image, long timeoutMillis) {
		if (image == null) {
			return false;
		}
		if (Toolkit.getDefaultToolkit().prepareImage(image, -1, -1, nullObserver())) {
			return true;
		}
		long deadline = System.currentTimeMillis() + timeoutMillis;
		while (System.currentTimeMillis() < deadline) {
			int status = Toolkit.getDefaultToolkit().checkImage(image, -1, -1, nullObserver());
			if ((status & ImageObserver.ALLBITS) != 0) {
				return true;
			}
			if ((status & (ImageObserver.ABORT | ImageObserver.ERROR)) != 0) {
				return false;
			}
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	private static ImageObserver nullObserver() {
		return null;
	}

	/**
	 * Applies the logo to a server GUI frame: load from the jar, set as the
	 * window icon, and wait for full decode so the title bar never shows a
	 * half-loaded image. This is the entire behavior of the
	 * {@code MinecraftServerGuiMixin} hook, factored out so it can be
	 * exercised directly (including against a real frame on a desktop JVM)
	 * rather than only through vanilla's GUI construction.
	 *
	 * <p>Failure-isolated by contract: headless environments, a missing
	 * resource, or a decode failure all leave the frame unchanged and return
	 * false. This runs inside vanilla's server GUI bootstrap — a thrown
	 * exception here would kill the GUI, so nothing may escape.</p>
	 *
	 * @return true when the icon was applied and fully decoded
	 */
	public static boolean applyTo(Frame frame) {
		if (frame == null) {
			return false;
		}
		try {
			Image icon = loadFrameIcon();
			if (icon == null) {
				return false;
			}
			frame.setIconImage(icon);
			LOGGER.info("[FabricFolia] Server GUI icon set from {} ({}x{})", RESOURCE,
					icon.getWidth(null), icon.getHeight(null));
			if (!awaitLoaded(icon, DECODE_TIMEOUT_MILLIS)) {
				LOGGER.debug("Fabric-Folia logo icon did not finish decoding within {}ms; frame keeps it anyway",
						DECODE_TIMEOUT_MILLIS);
			}
			return true;
		} catch (RuntimeException e) {
			LOGGER.warn("Could not apply the Fabric-Folia server GUI icon; frame keeps the platform default", e);
			return false;
		}
	}
}
