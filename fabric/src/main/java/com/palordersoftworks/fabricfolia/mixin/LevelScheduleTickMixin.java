/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Region-worker scheduled-tick deferral (mandate 21, spec 15): a region
 * worker executing a staged gameplay body may legitimately call
 * {@code level.scheduleTick} (fire spreading, water flowing, falling
 * blocks). Vanilla's {@code LevelTicks.schedule} mutates the container
 * immediately — on a worker that write races the server thread's own drain
 * and other regions' work.
 *
 * <p><strong>The deferral protocol:</strong> {@code LevelTicks.schedule} is
 * the single funnel every write takes (ScheduledTickAccess's scheduleTick
 * defaults construct the tick and hand it to the level's LevelTickAccess;
 * ServerLevel's blockTicks/fluidTicks ARE the LevelTicks containers).
 * When the caller is a region worker and the container belongs to a
 * staged world, the ALREADY-CONSTRUCTED tick object is captured verbatim —
 * no reconstruction, no semantic fork — and buffered for replay. Any other
 * caller (the server thread's own drain re-schedules, commands, console)
 * runs vanilla unchanged. At end of server tick the engine replays every
 * buffered tick on the server thread through its own container (this
 * thread is never in REGION context, so the replay passes straight
 * through), keeping the container single-writer. Deadlines are absolute
 * game-time ticks and are not recomputed.</p>
 */
@Mixin(LevelTicks.class)
public abstract class LevelScheduleTickMixin {

	@Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
	private <T> void fabricfolia$deferWorkerScheduleTick(ScheduledTick<T> tick, CallbackInfo ci) {
		if (ScheduledTickDeferral.capture((LevelTicks<T>) (Object) this, tick)) {
			ci.cancel(); // deferred to the end-of-tick server-thread replay
		}
		// Not captured (server thread, or an unregistered container): vanilla runs.
	}
}
