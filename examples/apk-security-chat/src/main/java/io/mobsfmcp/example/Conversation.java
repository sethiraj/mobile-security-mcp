// SPDX-License-Identifier: MIT
package io.mobsfmcp.example;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The messages shown in the browser, one conversation per HTTP session. */
@Component
@SessionScope
public class Conversation {

	public record Turn(String role, String text) {

		public boolean user() {
			return "user".equals(this.role);
		}

	}

	private final List<Turn> turns = new ArrayList<>();

	public List<Turn> turns() {
		return Collections.unmodifiableList(this.turns);
	}

	public void add(String role, String text) {
		this.turns.add(new Turn(role, text));
	}

	public void clear() {
		this.turns.clear();
	}

	public boolean isEmpty() {
		return this.turns.isEmpty();
	}

}
