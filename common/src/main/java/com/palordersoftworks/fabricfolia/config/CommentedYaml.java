/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.config;

import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.composer.Composer;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.parser.ParserImpl;
import org.snakeyaml.engine.v2.scanner.ScannerImpl;
import org.snakeyaml.engine.v2.scanner.StreamReader;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A minimal comment-preserving YAML mapping model built on SnakeYAML Engine's
 * LOW-LEVEL API (spec 13).
 *
 * <p><strong>Why the low-level API:</strong> the convenient load-to-Map / POJO
 * binding layers parse to Java objects and the comment stream never survives;
 * only the parser → composer → {@code Node} presentation-tree path preserves
 * comments ({@code LoadSettingsBuilder.setParseComments(true)} attaches them to
 * nodes as block/in-line/end comment lists). We operate on the Node tree
 * directly, and re-serialize with {@code DumpSettingsBuilder.setDumpComments(true)}
 * so administrator comments round-trip (verified by {@code CommentedYamlTest}).</p>
 *
 * <p><strong>Supported dialect:</strong> nested mappings of scalars with comments
 * — everything a config file needs. Sequences, anchors/aliases, multi-document
 * streams are outside the dialect and rejected explicitly rather than silently
 * mishandled.</p>
 */
public final class CommentedYaml {

	private final MappingNode root;

	private CommentedYaml(MappingNode root) {
		this.root = root;
	}

	/**
	 * Parses a single YAML document into a comment-preserving tree.
	 *
	 * @throws ConfigException on malformed YAML, a non-mapping root, or
	 *                         out-of-dialect constructs (sequences, anchors)
	 */
	public static CommentedYaml load(Reader reader) {
		LoadSettings settings = LoadSettings.builder()
				.setParseComments(true)
				.setAllowDuplicateKeys(false)
				.build();
		try {
			ParserImpl parser = new ParserImpl(settings, new ScannerImpl(settings, new StreamReader(settings, reader)));
			Composer composer = new Composer(settings, parser);
			Optional<Node> node = composer.getSingleNode();
			if (node.isEmpty()) {
				// Empty file: an empty mapping. Caller decides whether to write
				// the full default template.
				return new CommentedYaml(new MappingNode(Tag.MAP, new ArrayList<>(),
						org.snakeyaml.engine.v2.common.FlowStyle.BLOCK));
			}
			Node single = node.get();
			if (single.getTag() == Tag.COMMENT) {
				// A file containing only comments: same as empty for value
				// purposes, but comments will be re-emitted by the writer.
				return new CommentedYaml(new MappingNode(Tag.MAP, new ArrayList<>(),
						org.snakeyaml.engine.v2.common.FlowStyle.BLOCK));
			}
			if (!(single instanceof MappingNode map)) {
				throw new ConfigException("Config root must be a YAML mapping, found: " + single.getTag());
			}
			validateSupported(map, 0);
			return new CommentedYaml(map);
		} catch (ConfigException e) {
			throw e;
		} catch (Exception e) {
			throw new ConfigException(describeParseFailure(e), e);
		}
	}

	/**
	 * Converts a parse failure into an administrator-actionable message.
	 * SnakeYAML wraps NIO encoding errors (MalformedInputException) inside
	 * its own YAMLException, so a typed catch never sees them — the cause
	 * chain must be inspected. Found by the compatibility harness saving
	 * this file in a Windows legacy code page.
	 */
	private static String describeParseFailure(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof java.nio.charset.CharacterCodingException) {
				return "Configuration file is not valid UTF-8 (" + t.getMessage()
						+ "). Fabric Folia config files must be UTF-8 (no BOM); re-save the file as UTF-8, or delete it to regenerate the default template.";
			}
		}
		return "Failed to parse configuration YAML: " + e.getMessage();
	}

	private static void validateSupported(MappingNode map, int depth) {
		if (depth > 4) {
			throw new ConfigException("Config nesting deeper than 4 levels is not supported");
		}
		for (NodeTuple tuple : map.getValue()) {
			Node value = tuple.getValueNode();
			switch (value.getNodeType()) {
				case SCALAR -> { /* fine */ }
				case MAPPING -> validateSupported((MappingNode) value, depth + 1);
				case SEQUENCE -> throw new ConfigException(
						"YAML sequences are not used by the Fabric Folia config dialect (near key: "
								+ keyText(tuple) + ")");
				default -> throw new ConfigException("Unsupported YAML node type: " + value.getNodeType());
			}
		}
	}

	/**
	 * @return the scalar value at {@code dotted.key.path}, or null if absent.
	 *         Values are typed by YAML resolution: Boolean for true/false,
	 *         Integer for integers, String otherwise.
	 */
	public Object get(String dottedKey) {
		String[] parts = dottedKey.split("\\.");
		MappingNode current = root;
		for (int i = 0; i < parts.length - 1; i++) {
			Node next = child(current, parts[i]);
			if (!(next instanceof MappingNode m)) return null;
			current = m;
		}
		Node leaf = child(current, parts[parts.length - 1]);
		return leaf instanceof ScalarNode scalar ? scalarValue(scalar) : null;
	}

	// (scalarValue below avoids switch-on-Tag: Tag is a class in SnakeYAML
	// Engine, not an enum, so switch patterns do not apply.)

	/** @return true if the dotted key exists. */
	public boolean has(String dottedKey) {
		String[] parts = dottedKey.split("\\.");
		MappingNode current = root;
		for (int i = 0; i < parts.length - 1; i++) {
			Node next = child(current, parts[i]);
			if (!(next instanceof MappingNode m)) return false;
			current = m;
		}
		return child(current, parts[parts.length - 1]) != null;
	}

	private static Node child(MappingNode map, String key) {
		for (NodeTuple tuple : map.getValue()) {
			if (tuple.getKeyNode() instanceof ScalarNode scalar
					&& scalar.getValue().equals(key)) {
				return tuple.getValueNode();
			}
		}
		return null;
	}

	private static Object scalarValue(ScalarNode scalar) {
		Tag tag = scalar.getTag();
		if (tag == Tag.BOOL) {
			return Boolean.parseBoolean(scalar.getValue());
		}
		if (tag == Tag.INT) {
			try {
				return Integer.parseInt(scalar.getValue().trim());
			} catch (NumberFormatException e) {
				return scalar.getValue();
			}
		}
		return scalar.getValue();
	}

	/**
	 * Sets (replacing existing, or appending) a dotted key to a scalar value.
	 * Used by the loader for repaired/migrated values before save. Note: this
	 * does not carry documentation comments — for template generation use
	 * {@link ConfigWriter} which builds nodes from the schema.
	 */
	public void set(String dottedKey, Object value) {
		String[] parts = dottedKey.split("\\.");
		MappingNode current = root;
		for (int i = 0; i < parts.length - 1; i++) {
			Node next = child(current, parts[i]);
			if (!(next instanceof MappingNode m)) {
				MappingNode created = new MappingNode(Tag.MAP, new ArrayList<>(), org.snakeyaml.engine.v2.common.FlowStyle.BLOCK);
				current.getValue().add(new NodeTuple(stringKey(parts[i]), created));
				current = created;
			} else {
				current = m;
			}
		}
		String leafKey = parts[parts.length - 1];
		ScalarNode scalar = scalarNode(value);
		java.util.List<NodeTuple> tuples = current.getValue();
		for (int i = 0; i < tuples.size(); i++) {
			NodeTuple tuple = tuples.get(i);
			if (tuple.getKeyNode() instanceof ScalarNode s && s.getValue().equals(leafKey)) {
				tuples.set(i, new NodeTuple(tuple.getKeyNode(), scalar));
				return;
			}
		}
		tuples.add(new NodeTuple(stringKey(leafKey), scalar));
	}

	private static ScalarNode stringKey(String value) {
		return new ScalarNode(Tag.STR, value, org.snakeyaml.engine.v2.common.ScalarStyle.PLAIN);
	}

	private static ScalarNode scalarNode(Object value) {
		String str = String.valueOf(value);
		Tag tag = (value instanceof Boolean) ? Tag.BOOL
				: (value instanceof Integer) ? Tag.INT
				: Tag.STR;
		return new ScalarNode(tag, str, org.snakeyaml.engine.v2.common.ScalarStyle.PLAIN);
	}

	/** @return every leaf key path present in the tree, dotted. */
	public List<String> allKeys() {
		List<String> out = new ArrayList<>();
		collect(root, "", out);
		return out;
	}

	private static void collect(MappingNode map, String prefix, List<String> out) {
		for (NodeTuple tuple : map.getValue()) {
			String key = prefix.isEmpty() ? keyText(tuple) : prefix + "." + keyText(tuple);
			if (tuple.getValueNode() instanceof MappingNode m) {
				collect(m, key, out);
			} else {
				out.add(key);
			}
		}
	}

	private static String keyText(NodeTuple tuple) {
		return tuple.getKeyNode() instanceof ScalarNode s ? s.getValue() : tuple.getKeyNode().toString();
	}

	/** @return the raw root node for the writer. */
	public MappingNode root() {
		return root;
	}
}
