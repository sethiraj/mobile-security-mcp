// SPDX-License-Identifier: MIT
package io.mobsfmcp.example.appium;

import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;
import jakarta.annotation.PreDestroy;

import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lets the model drive the app on the device MobSF is analysing.
 * <p>
 * MobSF's own activity tester launches each activity and screenshots it, which is enough
 * to find an exported screen but not to reach anything behind a login or a form. Appium
 * covers that gap: the model can read the screen, tap and type, so the app reaches the
 * states where it actually uses the network while MobSF's Frida hooks are recording.
 * <p>
 * One session at a time, deliberately: two drivers on one device fight over the UI.
 */
@Component
public class AppiumTools {

	private static final int MAX_ELEMENTS = 40;

	private final AppiumProperties properties;

	private AndroidDriver driver;

	public AppiumTools(AppiumProperties properties) {
		this.properties = properties;
	}

	@Tool(description = "Open an Appium session on the Android device, installing the app if a path is given. "
			+ "Call this before any other appium tool, and use the same device MobSF is analysing.")
	public synchronized String appiumStartSession(
			@ToolParam(description = "Path to the APK on this machine, or empty to use the installed app",
					required = false) String apkPath,
			@ToolParam(description = "Package to launch, e.g. io.selendroid.testapp",
					required = false) String packageName) {
		if (this.driver != null) {
			return "A session is already open. Call appiumStopSession first for a fresh one.";
		}
		try {
			UiAutomator2Options options = new UiAutomator2Options().setDeviceName(this.properties.deviceName())
				.setUdid(this.properties.deviceName())
				.setNewCommandTimeout(this.properties.commandTimeout())
				.setAutoGrantPermissions(true)
				.setNoReset(true);
			if (this.properties.platformVersion() != null && !this.properties.platformVersion().isBlank()) {
				options.setPlatformVersion(this.properties.platformVersion());
			}
			if (apkPath != null && !apkPath.isBlank()) {
				File apk = new File(apkPath);
				if (!apk.isFile()) {
					return "No APK at " + apkPath + ". Give a path on the machine running this chat app.";
				}
				options.setApp(apk.getAbsolutePath());
			}
			else if (packageName != null && !packageName.isBlank()) {
				options.setAppPackage(packageName);
			}
			URL server = URI.create(this.properties.serverUrl()).toURL();
			this.driver = new AndroidDriver(server, options);
			return "Session open on " + this.properties.deviceName() + ". Current activity: " + currentActivity();
		}
		catch (Exception ex) {
			this.driver = null;
			return "Could not start the Appium session (" + ex.getClass().getSimpleName() + ": " + ex.getMessage()
					+ "). Check that an Appium server is listening at " + this.properties.serverUrl()
					+ " and that 'adb devices' lists " + this.properties.deviceName() + ".";
		}
	}

	@Tool(description = "List what is on screen now: the current activity, and every visible text, button and "
			+ "input field. Use it to decide what to tap or type next.")
	public synchronized String appiumReadScreen() {
		if (this.driver == null) {
			return notStarted();
		}
		try {
			List<String> lines = new ArrayList<>();
			lines.add("activity: " + currentActivity());
			List<WebElement> elements = this.driver
				.findElements(By.xpath("//*[string-length(@text)>0 or @clickable='true']"));
			for (int i = 0; i < elements.size(); i++) {
				if (i >= MAX_ELEMENTS) {
					lines.add("... and " + (elements.size() - MAX_ELEMENTS) + " more");
					break;
				}
				lines.add(describe(elements.get(i)));
			}
			return String.join("\n", lines);
		}
		catch (RuntimeException ex) {
			return "Could not read the screen: " + ex.getMessage();
		}
	}

	@Tool(description = "Tap the element whose visible text, content description or resource id matches. "
			+ "Use the exact text that appiumReadScreen returned.")
	public synchronized String appiumTap(@ToolParam(description = "Text, content-desc or resource id") String target) {
		if (this.driver == null) {
			return notStarted();
		}
		WebElement element = find(target);
		if (element == null) {
			return "Nothing on screen matches '" + target + "'. Call appiumReadScreen to see what is there.";
		}
		element.click();
		return "Tapped '" + target + "'. Now on: " + currentActivity();
	}

	@Tool(description = "Type into the matching input field. Use this to get past logins and forms, so the app "
			+ "reaches the screens that actually use the network.")
	public synchronized String appiumType(
			@ToolParam(description = "Text, content-desc or resource id of the field") String target,
			@ToolParam(description = "What to type") String text) {
		if (this.driver == null) {
			return notStarted();
		}
		WebElement element = find(target);
		if (element == null) {
			return "No field matches '" + target + "'. Call appiumReadScreen first.";
		}
		element.clear();
		element.sendKeys(text);
		return "Typed into '" + target + "'.";
	}

	@Tool(description = "Press a hardware key: back, home or enter.")
	public synchronized String appiumPressKey(@ToolParam(description = "back, home or enter") String key) {
		if (this.driver == null) {
			return notStarted();
		}
		int code = switch (key == null ? "" : key.trim().toLowerCase()) {
			case "back" -> 4;
			case "home" -> 3;
			case "enter" -> 66;
			default -> -1;
		};
		if (code < 0) {
			return "Unknown key '" + key + "'. Use back, home or enter.";
		}
		this.driver.executeScript("mobile: pressKey", Map.of("keycode", code));
		return "Pressed " + key + ". Now on: " + currentActivity();
	}

	@Tool(description = "Close the Appium session. The app stays installed, so MobSF can still collect its data.")
	public synchronized String appiumStopSession() {
		if (this.driver == null) {
			return "No session was open.";
		}
		quit();
		return "Session closed.";
	}

	private WebElement find(String target) {
		String escaped = target.replace("'", "");
		List<By> strategies = List.of(By.xpath("//*[@text='" + escaped + "']"),
				By.xpath("//*[@content-desc='" + escaped + "']"),
				By.xpath("//*[contains(@resource-id,'" + escaped + "')]"),
				By.xpath("//*[contains(@text,'" + escaped + "')]"));
		for (By by : strategies) {
			List<WebElement> found = this.driver.findElements(by);
			if (!found.isEmpty()) {
				return found.get(0);
			}
		}
		return null;
	}

	private String describe(WebElement element) {
		String text = attribute(element, "text");
		String desc = attribute(element, "content-desc");
		String id = attribute(element, "resource-id");
		String clazz = attribute(element, "class");
		StringBuilder line = new StringBuilder("- ");
		line.append(clazz.isBlank() ? "view" : clazz.substring(clazz.lastIndexOf('.') + 1));
		if (!text.isBlank()) {
			line.append(" \"").append(text).append('"');
		}
		if (!desc.isBlank()) {
			line.append(" desc=").append(desc);
		}
		if (!id.isBlank()) {
			line.append(" id=").append(id.substring(id.lastIndexOf('/') + 1));
		}
		if ("true".equals(attribute(element, "clickable"))) {
			line.append(" [clickable]");
		}
		return line.toString();
	}

	private static String attribute(WebElement element, String name) {
		try {
			String found = element.getDomAttribute(name);
			return found == null ? "" : found;
		}
		catch (RuntimeException ex) {
			return "";
		}
	}

	private String currentActivity() {
		try {
			return this.driver == null ? "none" : String.valueOf(this.driver.currentActivity());
		}
		catch (RuntimeException ex) {
			return "unknown";
		}
	}

	private static String notStarted() {
		return "No Appium session is open. Call appiumStartSession first.";
	}

	@PreDestroy
	synchronized void quit() {
		if (this.driver != null) {
			try {
				this.driver.quit();
			}
			catch (RuntimeException ignored) {
				// the device may already be gone; nothing useful to do about it
			}
			this.driver = null;
		}
	}

}
