// SPDX-License-Identifier: MIT
package io.mobsfmcp.util;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

/**
 * Reading values out of MobSF's JSON, where "no value" arrives in three different shapes.
 * <p>
 * MobSF's Django models default to {@code ''}, so a missing app name is an empty string
 * rather than null — {@code /api/v1/scans} returns {@code APP_NAME: ""} and
 * {@code PACKAGE_NAME: ""} for scans that have neither. Some endpoints do use JSON null
 * ({@code identifier} on {@code dynamic/get_apps}), and some fields are absent entirely.
 * <p>
 * All three mean the same thing to a caller, so everything here collapses them to
 * {@code null}: an agent can then tell "MobSF did not provide this" from a real value,
 * and grouping code never keys on an empty string.
 */
public final class Json {

	private Json() {
	}

	/** Field as text, with missing, null and blank all collapsed to {@code null}. */
	public static String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		return blankToNull(value.isTextual() ? value.asString() : value.toString());
	}

	/** Trimmed string, or {@code null} when it is null, empty or only whitespace. */
	public static String blankToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** First non-blank of the candidates, or {@code null}. */
	public static String firstNonBlank(String... candidates) {
		for (String candidate : candidates) {
			String value = blankToNull(candidate);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	/** Numeric field, or {@code null} when absent or not a number (MobSF sometimes sends strings). */
	public static Integer intOrNull(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.path(field);
		return value.isNumber() ? value.asInt() : null;
	}

	public static Double doubleOrNull(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.path(field);
		return value.isNumber() ? value.asDouble() : null;
	}

	/**
	 * Array (or object keys) as a list of strings, skipping blanks. Safe on missing nodes
	 * and on nulls, both of which yield an empty list.
	 */
	public static List<String> strings(JsonNode node) {
		List<String> values = new ArrayList<>();
		if (node == null || node.isMissingNode() || node.isNull()) {
			return List.of();
		}
		if (node.isObject()) {
			node.propertyStream().forEach(entry -> {
				String key = blankToNull(entry.getKey());
				if (key != null) {
					values.add(key);
				}
			});
			return List.copyOf(values);
		}
		for (JsonNode item : node) {
			String value = item.isTextual() ? blankToNull(item.asString()) : blankToNull(item.toString());
			if (value != null) {
				values.add(value);
			}
		}
		return List.copyOf(values);
	}

}
