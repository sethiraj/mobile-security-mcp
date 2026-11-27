// SPDX-License-Identifier: MIT
package io.mobsfmcp.example;

import io.mobsfmcp.example.appium.AppiumProperties;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
class ChatController {

	private static final List<String> SUGGESTIONS = List.of(
			"Scan /data/apks/selendroid-test-app-0.17.0.apk and summarise the high findings",
			"Is a device connected for dynamic analysis?",
			"Run the app on the emulator, tap through it, and tell me what it sends in the clear");

	private final ChatClient chat;

	private final Conversation conversation;

	private final AppiumProperties appium;

	ChatController(ChatClient chat, Conversation conversation, AppiumProperties appium) {
		this.chat = chat;
		this.conversation = conversation;
		this.appium = appium;
	}

	@GetMapping("/")
	String index(Model model) {
		model.addAttribute("turns", this.conversation.turns());
		model.addAttribute("empty", this.conversation.isEmpty());
		model.addAttribute("suggestions", SUGGESTIONS);
		model.addAttribute("device", this.appium.deviceName());
		return "chat";
	}

	@PostMapping("/ask")
	String ask(@RequestParam String message, Model model) {
		if (!StringUtils.hasText(message)) {
			return index(model);
		}
		this.conversation.add("user", message);
		try {
			String answer = this.chat.prompt().user(message).call().content();
			this.conversation.add("assistant", StringUtils.hasText(answer) ? answer : "(the model returned nothing)");
		}
		catch (RuntimeException ex) {
			// Surfaced rather than swallowed: a failing tool call or a bad endpoint is the
			// most likely thing to go wrong here, and it is useless if the UI hides it.
			this.conversation.add("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
		}
		return "redirect:/";
	}

	@PostMapping("/reset")
	String reset() {
		this.conversation.clear();
		return "redirect:/";
	}

}
