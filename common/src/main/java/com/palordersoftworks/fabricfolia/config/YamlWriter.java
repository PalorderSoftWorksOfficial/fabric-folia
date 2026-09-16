/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.config;

import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.StreamDataWriter;
import org.snakeyaml.engine.v2.api.lowlevel.Serialize;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.emitter.Emitter;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.nodes.Node;

import java.io.StringWriter;
import java.util.List;

/**
 * Emits a comment-preserving Node tree back to YAML text.
 *
 * <p><strong>Path (verified against SnakeYAML Engine 3.1.1 sources):</strong>
 * {@code Serialize.serializeOne(node)} produces the event stream — including
 * {@code CommentEvent}s translated from the node's block/in-line/end comment
 * lists when {@code DumpSettingsBuilder.setDumpComments(true)} — and the
 * {@code Emitter} renders that event stream to text.</p>
 *
 * <p><strong>Why this wrapper exists:</strong> to pin the settings that matter
 * for a config file in one place: comments dumped, block style, no line
 * wrapping (never reflow an operator's documentation), no canonical output.</p>
 */
public final class YamlWriter {

	private YamlWriter() {
	}

	private static DumpSettings settings() {
		return DumpSettings.builder()
				.setDumpComments(true)          // the entire point — see class docs
				.setDefaultFlowStyle(FlowStyle.BLOCK)
				.setIndent(2)
				.setWidth(4096)                 // never wrap comment text or long keys
				.setSplitLines(false)
				.build();
	}

	/**
	 * Serializes a Node tree to YAML text with comments preserved.
	 *
	 * @param root document root node (MappingNode for configs)
	 * @return the YAML text, ending with a newline
	 */
	public static String write(Node root) {
		StringWriter sw = new StringWriter();
		Serialize serialize = new Serialize(settings());
		List<Event> events = serialize.serializeOne(root);

		Emitter emitter = new Emitter(settings(), new WriterAdapter(sw));
		for (Event event : events) {
			emitter.emit(event);
		}
		String text = sw.toString();
		return text.endsWith("\n") ? text : text + "\n";
	}

	/** Adapts StringWriter to SnakeYAML's StreamDataWriter sink interface. */
	private static final class WriterAdapter implements StreamDataWriter {
		private final StringWriter delegate;

		WriterAdapter(StringWriter delegate) {
			this.delegate = delegate;
		}

		@Override
		public void write(String str) {
			delegate.write(str);
		}

		@Override
		public void write(String str, int off, int len) {
			delegate.write(str, off, len);
		}

		@Override
		public void flush() {
			delegate.flush();
		}
	}
}
