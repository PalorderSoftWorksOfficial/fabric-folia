/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.gui.ServerGuiIcon;
import javax.swing.JFrame;
import net.minecraft.server.gui.MinecraftServerGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Applies the Fabric-Folia logo ({@code logo.png}, jar root) as the
 * dedicated-server GUI frame icon — the Fabric-native equivalent of Folia's
 * upstream "use Folia logo" change to {@code MinecraftServerGui}.
 *
 * <p>Vanilla never sets a frame icon (verified by javap against 26.2: no
 * {@code setIconImage} reference in the class), so the icon must be applied
 * from outside. {@code showFrameFor} creates the frame in a method local —
 * a plain {@code @Inject} cannot reach it (the live harness proved this: a
 * callback there receives only the host method's {@code DedicatedServer}
 * parameter, and a wrong-descriptor handler fails mixin apply, which with a
 * required config crashes server boot). The frame reference exists at
 * exactly one interception point: its own {@code setVisible} invocation.
 * Redirecting that call applies the icon and then shows the frame — the
 * icon is set before the window ever paints a default one.</p>
 *
 * <p>All behavior lives in {@link ServerGuiIcon#applyTo}, which is
 * failure-isolated by contract: headless-safe, missing-resource-safe, and it
 * catches its own runtime failures so a broken icon can never disturb the
 * server GUI bootstrap.</p>
 */
@Mixin(MinecraftServerGui.class)
public abstract class MinecraftServerGuiMixin {

	@Redirect(
			method = "showFrameFor(Lnet/minecraft/server/dedicated/DedicatedServer;)Lnet/minecraft/server/gui/MinecraftServerGui;",
			at = @At(
					value = "INVOKE",
					target = "Ljavax/swing/JFrame;setVisible(Z)V"
			)
	)
	private static void fabricfolia$applyIconThenShow(JFrame frame, boolean visible) {
		ServerGuiIcon.applyTo(frame);
		frame.setVisible(visible);
	}
}
