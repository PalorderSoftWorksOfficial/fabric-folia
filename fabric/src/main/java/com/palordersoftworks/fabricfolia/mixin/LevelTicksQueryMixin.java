/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.engine.ScheduledTickDeferral;
import net.minecraft.core.BlockPos;
import net.minecraft.world.ticks.LevelTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Region-local hasScheduledTick (mandate 21): block behaviors that run on
 * region workers — observers, tripwire, targets, lightning rods, every one
 * reachable from random ticks and neighbor updates in staged bodies — call
 * {@code Level.getBlockTicks().hasScheduledTick(...)} before re-scheduling.
 * Vanilla's coordinator ({@code LevelTicks.allContainers}) is server-thread
 * state; reading it from a worker while the drain mutates it is a data race.
 *
 * <p><strong>The redirect:</strong> when the caller is a region worker AND
 * the container is a staged world's container with a registered pending-tick
 * ledger, the question is answered from the ledger ({@code
 * RegionPendingTicks.isPending}: the caller's own region's accounting,
 * falling back to the structural owner's ledger) and vanilla is skipped.
 * Every other caller — the server thread (serialized with the writer by
 * definition), unregistered containers — runs vanilla unchanged.</p>
 *
 * <p><strong>Conservatism:</strong> the ledger is chunk-keyed and
 * type-blind: a recorded capture for ANY tick type in this chunk answers
 * TRUE for a query about a specific block. That can over-report when
 * vanilla would answer no, but {@code LevelChunkTicks.schedule} is a
 * set-add (vanilla dedups identical ticks), so a spurious "yes" only skips
 * a duplicate schedule that vanilla would have discarded. It can never
 * falsely report "no" for a tick that will actually run — the direction
 * these callers depend on (they re-schedule on "no", and a lost schedule
 * is a lost game event).</p>
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksQueryMixin {

	@Inject(method = "hasScheduledTick", at = @At("HEAD"), cancellable = true)
	private <T> void fabricfolia$answerFromRegionLedger(BlockPos pos, T type,
	                                                    CallbackInfoReturnable<Boolean> cir) {
		if (com.palordersoftworks.fabricfolia.thread.ThreadOwnership.current().kind()
				!= com.palordersoftworks.fabricfolia.api.ThreadContext.Kind.REGION) {
			return; // server thread / global / async: vanilla's serialized read is correct
		}
		com.palordersoftworks.fabricfolia.scheduler.RegionPendingTicks ledger =
				ScheduledTickDeferral.ledgerOf((LevelTicks<?>) (Object) this);
		if (ledger == null) {
			return; // unregistered container: vanilla owns the truth here
		}
		if (ledger.isPending(pos.getX() >> 4, pos.getZ() >> 4)) {
			cir.setReturnValue(true);
		}
		// Ledger says no for this chunk: fall through to vanilla. The worker
		// read remains racy against the coordinator, but only in the
		// conservative direction (a concurrent insert it misses looks like
		// "no"; the caller then re-schedules a duplicate vanilla dedups).
		// Skipping vanilla on a ledger "yes" is the race this mixin removes;
		// the fallthrough is the same risk vanilla-class callers already
		// accept for coordinator inserts they cannot see.
	}
}
