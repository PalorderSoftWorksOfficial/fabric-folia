/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.RegionStageHub;
import com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral;

import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the scheduled-tick drain window (mandate 21): {@code ServerLevel.tick}
 * invokes {@code blockTicks.tick(...)} and {@code fluidTicks.tick(...)}, each
 * with a {@code ServerLevel::tickBlock}/{@code tickFluid} BiConsumer. The
 * drain staging flag is raised for this world's drain when its staging is
 * active, so {@code LevelTicksTickMixin} stages the drained bodies into the
 * {@code SCHEDULED_TICK} slice — and only for staged worlds; other mods'
 * LevelTicks instances and staging-inactive worlds drain as vanilla.
 *
 * <p>Server thread only (the drains run there). Flag is set/cleared around
 * the whole tick method: it is a thread-local that only matters inside the
 * two drain calls, and clearing in a {@code finally} guarantees no leak
 * even if a drain throws (vanilla's own crash reporting proceeds on the
 * un-flagged thread).</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTickMixin {

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
	private void fabricfolia$beginDrainWindow(CallbackInfo ci) {
		if (RegionStageHub.isStaging((ServerLevel) (Object) this, RegionStageHub.Slice.SCHEDULED_TICK)) {
			ScheduledTickDeferral.beginDrainStaging();
		}
	}

	@Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"))
	private void fabricfolia$endDrainWindow(CallbackInfo ci) {
		ScheduledTickDeferral.endDrainStaging();
	}
}
