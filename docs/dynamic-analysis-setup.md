# Setting up a device for dynamic analysis

Dynamic analysis runs the app on a real Android device or emulator that MobSF drives over
adb. The MCP server itself needs no Android tooling: MobSF owns adb and Frida, and the
server only calls MobSF's REST API.

```
mobsf-mcp  ──REST──>  MobSF container  ──adb over TCP──>  Android device/emulator
```

## What the device must be
- **Rooted, with a writable system.** MobSF pushes a Frida server and its own CA certificate.
- **Not a Google Play image.** Play images are not rootable. Use an "AOSP"/"Google APIs" image, or Genymotion.
- Confirm the Android versions your MobSF release supports; MobSF's own docs list them, and its dynamic analyzer page reports a version mismatch.

Recommended: **Genymotion Desktop** (personal use is free, rooted by default, easy on Windows and macOS), or an **Android Studio AVD** started with `-writable-system`.

## Windows or macOS host (Docker Desktop)
The MobSF container cannot see `localhost` on your machine, so the emulator is reached through `host.docker.internal`.

1. Start the emulator on the host.
   - Genymotion: start the virtual device; it is already listening on adb.
   - AVD: `emulator -avd <name> -writable-system -no-snapshot`
2. Check adb sees it, and note the port:
   ```bash
   adb devices          # e.g. emulator-5554, or 192.168.56.101:5555 for Genymotion
   adb tcpip 5555       # only needed for a USB phone
   ```
3. Point MobSF at it in `.env`, then restart:
   ```
   MOBSF_ANALYZER_IDENTIFIER=host.docker.internal:5555
   ```
   ```bash
   docker compose up -d
   ```
   For Genymotion use its own IP instead, for example `192.168.56.101:5555`.
4. Prepare the device once, through MCP: call `mobsfy_device` with the same identifier. MobSF installs the Frida server, its root CA and the proxy settings.
5. Call `device_status`. It should list the packages on the device.

## Linux with KVM
Use the bundled emulator service instead of a host emulator:
```bash
docker compose --profile emulator up -d
# MOBSF_ANALYZER_IDENTIFIER=mobsf-emulator:5555
```
It needs `/dev/kvm`, so it does not work on Docker Desktop for Windows or macOS.

## A physical phone
The phone must be rooted (Magisk). Connect it over USB, then:
```bash
adb tcpip 5555
adb connect <phone-ip>:5555
```
Set `MOBSF_ANALYZER_IDENTIFIER=<phone-ip>:5555`. Use a test device: MobSF installs a CA certificate and a Frida server on it.

## The workflow through MCP
```
device_status          -> is a device attached?
mobsfy_device          -> once per device
upload_app + scan_app  -> static scan first; dynamic analysis needs the hash
start_dynamic          -> installs and launches the app
test_activities        -> exercises activities (exportedOnly=true finds access-control bugs)
frida_instrument       -> e.g. defaultHooks="api_monitor,ssl_pinning_bypass"
run_tls_tests          -> cleartext, certificate validation, pinning
get_logcat             -> secrets the app logs at runtime
stop_dynamic           -> collects logs, screenshots and app data
export_report type=combined  -> static + dynamic findings in one JSON file
```

## Troubleshooting
| Symptom | Cause and fix |
|---|---|
| `device_status` reports no device | `MOBSF_ANALYZER_IDENTIFIER` unset or wrong. Check `adb devices` on the host, and use `host.docker.internal` rather than `localhost` in Docker |
| MobSF reports a Frida or root error | The image is not rooted, or the system is not writable. Use a non-Play image, and start an AVD with `-writable-system` |
| `mobsfy_device` fails | MobSF cannot reach the device over adb. Check the port, and that the emulator is running |
| HTTPS traffic is not captured | The app pins certificates. Use `frida_instrument` with `ssl_pinning_bypass` |
| Everything is empty after `stop_dynamic` | The app was not exercised. Use `test_activities` or `start_activity` between start and stop |

## Not available
- **Android screenshots on demand:** MobSF has no routed API for this (the view exists but is not exposed), so there is no screenshot tool. Screenshots are still collected by `test_activities` and appear in the dynamic report.
- **iOS dynamic analysis:** it needs Corellium or a jailbroken device. MobSF exposes the endpoints, but they are out of scope for now.
