/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.mixin;

import com.palordersoftworks.fabricfolia.thread.WorkerRandoms;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Wraps {@link Level#random} with the {@link WorkerRandoms} dispatcher at
 * construction (the per-engine-thread random sources — see
 * {@link WorkerRandoms} for the measured defect and the dispatch rule).
 *
 * <p><strong>Why the constructor:</strong> bytecode-verified on the 26.2 jar:
 * {@code Level.random} is a {@code protected final} field assigned exactly
 * once, in the single {@code Level} constructor
 * ({@code RandomSource.create()}). Every consumer — the internal reads in
 * {@code tickChunk}/{@code tickBlock}/weather and the public
 * {@code getRandom()} accessor — reads that one field, so wrapping it here
 * covers every funnel by construction and both overworld and per-dimension
 * levels (the ctor is the only creation path).</p>
 *
 * <p><strong>Why TAIL + @Mutable:</strong> the wrap happens after vanilla's
 * own assignment, replacing the instance reference without touching vanilla's
 * seeding ({@code create()}'s unique-seed behavior is unchanged). The field
 * is {@code final}, so the assignment requires Mixin's {@code @Mutable}
 * merge-time final-field relaxation. The wrapper is inert until the engine
 * activates it (see {@link WorkerRandoms#wrap}), so worlds constructed before
 * engine bootstrap are covered too, with zero behavior change until then.</p>
 *
 * <p><strong>State classification (spec 4):</strong> touches only the field
 * reference at construction (server-thread init); the dispatch itself is
 * documented on {@link WorkerRandoms}.</p>
 */
@Mixin(Level.class)
public abstract class LevelMixin {

	@Mutable
	@Shadow
	@Final
	protected RandomSource random;

	/**
	 * Empty handler is valid here: nothing is captured, the wrap only needs
	 * {@code this} (the constructed level) at completion.
	 */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void fabricfolia$wrapLevelRandom(CallbackInfo ci) {
		this.random = WorkerRandoms.wrap(this.random);
	}
}
