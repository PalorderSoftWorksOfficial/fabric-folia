/*
 * Fabric-Folia — regionized multithreaded execution for vanilla Minecraft.
 * Copyright 2026 Palorder Softworks. Apache-2.0 — see LICENSE.
 */

package com.palordersoftworks.fabricfolia.config;

import org.snakeyaml.engine.v2.comments.CommentLine;
import org.snakeyaml.engine.v2.comments.CommentType;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds and merges comment-preserving config Node trees.
 *
 * <p>Three operations drive the config lifecycle:</p>
 * <ol>
 *   <li>{@link #buildDefaultTree} — the full documented template from the
 *       schema (spec 11: a complete self-documenting file, not a stub).</li>
 *   <li>{@link #mergePreservingComments} — operator tree ∪ schema: schema keys
 *       missing from the operator file are appended (with docs); operator
 *       comments are preserved verbatim; unknown operator keys survive, flagged
 *       with a generated comment noting they are unrecognized.</li>
 *   <li>{@link YamlWriter#write} — emits the merged tree with comments.</li>
 * </ol>
 */
public final class ConfigWriter {

	private ConfigWriter() {
	}

	/**
	 * Builds the complete documented default config tree from the schema.
	 */
	public static MappingNode buildDefaultTree(ConfigSchema schema) {
		MappingNode root = newMapping();
		appendVersionEntry(root, schema);

		for (ConfigSchema.Option<?> option : schema.options()) {
			String[] parts = option.key().split("\\.");
			MappingNode current = root;
			for (int i = 0; i < parts.length - 1; i++) {
				current = childSectionOrCreate(current, parts[i]);
			}
			appendOptionEntry(current, option, null);
		}
		return root;
	}

	/** Finds or creates the mapping node for a nested section name. */
	private static MappingNode childSectionOrCreate(MappingNode parent, String name) {
		NodeTuple tuple = findTuple(parent, name);
		if (tuple != null && tuple.getValueNode() instanceof MappingNode existing) {
			return existing;
		}
		MappingNode created = newMapping();
		parent.getValue().add(new NodeTuple(stringKey(name), created));
		return created;
	}

	private static void appendVersionEntry(MappingNode root, ConfigSchema schema) {
		List<CommentLine> comments = new ArrayList<>();
		comments.add(new CommentLine(Optional.empty(), Optional.empty(),
				" Fabric-Folia configuration.", CommentType.BLOCK));
		comments.add(new CommentLine(Optional.empty(), Optional.empty(),
				" This file is comment-preserving: your edits and notes survive saves.",
				CommentType.BLOCK));
		comments.add(new CommentLine(Optional.empty(), Optional.empty(),
				" Unknown keys are kept (flagged) rather than silently removed.",
				CommentType.BLOCK));
		ScalarNode key = stringKey(ConfigSchema.KEY_VERSION);
		key.setBlockComments(comments);
		ScalarNode value = new ScalarNode(Tag.INT,
				Integer.toString(ConfigSchema.CURRENT_VERSION), ScalarStyle.PLAIN);
		root.getValue().add(new NodeTuple(key, value));
	}

	private static void appendOptionEntry(MappingNode section, ConfigSchema.Option<?> option,
	                                      List<CommentLine> replacementComments) {
		ScalarNode key = stringKey(leafOf(option.key()));
		if (replacementComments != null) {
			key.setBlockComments(replacementComments);
		} else {
			List<CommentLine> comments = new ArrayList<>();
			for (String line : option.commentLines()) {
				comments.add(new CommentLine(Optional.empty(), Optional.empty(),
						line.isEmpty() ? "" : " " + line, CommentType.BLOCK));
			}
			key.setBlockComments(comments);
		}
		Object def = option.defaultValue();
		String text = def instanceof Integer i ? Integer.toString(i) : String.valueOf(def);
		Tag tag = def instanceof Boolean ? Tag.BOOL : def instanceof Integer ? Tag.INT : Tag.STR;
		ScalarNode value = new ScalarNode(tag, text, ScalarStyle.PLAIN);
		section.getValue().add(new NodeTuple(key, value));
	}

	/**
	 * Merges the operator's parsed tree with the schema:
	 * <ul>
	 *   <li>existing operator values and comments are kept verbatim,</li>
	 *   <li>schema options absent from the operator file are appended with
	 *       their documentation,</li>
	 *   <li>operator keys unknown to the schema are kept, flagged with a
	 *       generated warning comment,</li>
	 *   <li>config-version is always rewritten to the current version.</li>
	 * </ul>
	 */
	public static MappingNode mergePreservingComments(CommentedYaml operatorFile, ConfigSchema schema) {
		MappingNode merged = operatorFile.root();

		for (ConfigSchema.Option<?> option : schema.options()) {
			if (!operatorFile.has(option.key())) {
				appendMissing(merged, option);
			}
		}
		flagUnknownKeys(merged, schema, "", 0);
		setScalar(merged, ConfigSchema.KEY_VERSION, Integer.toString(ConfigSchema.CURRENT_VERSION), Tag.INT);
		return merged;
	}

	private static void appendMissing(MappingNode root, ConfigSchema.Option<?> option) {
		String[] parts = option.key().split("\\.");
		MappingNode current = root;
		for (int i = 0; i < parts.length - 1; i++) {
			NodeTuple tuple = findTuple(current, parts[i]);
			if (tuple == null) {
				MappingNode created = newMapping();
				current.getValue().add(new NodeTuple(stringKey(parts[i]), created));
				current = created;
			} else if (tuple.getValueNode() instanceof MappingNode nested) {
				current = nested;
			} else {
				// A scalar occupies the section name: replace it (operator data
				// is invalid; schema structure wins; the invalid scalar is
				// dropped).
				MappingNode created = newMapping();
				replaceTupleValue(current, parts[i], created);
				current = created;
			}
		}
		appendOptionEntry(current, option, null);
	}

	private static void flagUnknownKeys(MappingNode node, ConfigSchema schema,
	                                    String prefix, int depth) {
		for (NodeTuple tuple : new ArrayList<>(node.getValue())) {
			if (!(tuple.getKeyNode() instanceof ScalarNode key)) {
				continue;
			}
			String name = key.getValue();
			String path = prefix.isEmpty() ? name : prefix + "." + name;
			if (name.equals(ConfigSchema.KEY_VERSION)) {
				continue;
			}
			if (tuple.getValueNode() instanceof MappingNode child) {
				flagUnknownKeys(child, schema, path, depth + 1);
			} else {
				if (schema.option(path) == null) {
					List<CommentLine> comments = new ArrayList<>(key.getBlockComments());
					comments.add(new CommentLine(Optional.empty(), Optional.empty(),
							" [FabricFolia] NOTE: this key is not recognized by this version;",
							CommentType.BLOCK));
					comments.add(new CommentLine(Optional.empty(), Optional.empty(),
							" it has been preserved. It may belong to a newer or older",
							CommentType.BLOCK));
					comments.add(new CommentLine(Optional.empty(), Optional.empty(),
							" version, or be a typo. Remove it if unintended.",
							CommentType.BLOCK));
					key.setBlockComments(comments);
				}
			}
		}
	}

	private static void setScalar(MappingNode root, String dottedKey, String value, Tag tag) {
		String[] parts = dottedKey.split("\\.");
		MappingNode current = root;
		for (int i = 0; i < parts.length - 1; i++) {
			NodeTuple t = findTuple(current, parts[i]);
			if (t == null || !(t.getValueNode() instanceof MappingNode m)) {
				throw new IllegalStateException("Missing section for key: " + dottedKey);
			}
			current = m;
		}
		ScalarNode scalar = new ScalarNode(tag, value, ScalarStyle.PLAIN);
		NodeTuple existing = findTuple(current, parts[parts.length - 1]);
		if (existing != null) {
			replaceTupleValue(current, parts[parts.length - 1], scalar);
		} else {
			current.getValue().add(new NodeTuple(stringKey(parts[parts.length - 1]), scalar));
		}
	}

	private static NodeTuple findTuple(MappingNode map, String key) {
		for (NodeTuple tuple : map.getValue()) {
			if (tuple.getKeyNode() instanceof ScalarNode s && s.getValue().equals(key)) {
				return tuple;
			}
		}
		return null;
	}

	private static void replaceTupleValue(MappingNode map, String key,
	                                      org.snakeyaml.engine.v2.nodes.Node newValue) {
		java.util.List<NodeTuple> tuples = map.getValue();
		for (int i = 0; i < tuples.size(); i++) {
			NodeTuple tuple = tuples.get(i);
			if (tuple.getKeyNode() instanceof ScalarNode s && s.getValue().equals(key)) {
				tuples.set(i, new NodeTuple(tuple.getKeyNode(), newValue));
				return;
			}
		}
	}

	private static MappingNode newMapping() {
		return new MappingNode(Tag.MAP, new ArrayList<>(), FlowStyle.BLOCK);
	}

	private static ScalarNode stringKey(String value) {
		return new ScalarNode(Tag.STR, value, ScalarStyle.PLAIN);
	}

	private static String leafOf(String dottedKey) {
		return dottedKey.substring(dottedKey.lastIndexOf('.') + 1);
	}
}
