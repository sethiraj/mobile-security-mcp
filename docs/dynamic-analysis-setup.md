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

## The recipe that actually works (verified on API 30, emulator 36.3.10)

MobSF needs a writable `/system` to install its Frida server and CA. Two things cost hours
here, so they are worth stating plainly:

**Use `avbctl`, not `disable-verity`.** `adb disable-verity` leaves an API 30 image
**unbootable** — adbd never comes back after the reboot. This sequence works:

```bash
emulator -avd <name> -writable-system -no-snapshot -no-audio -no-boot-anim -gpu swiftshader_indirect
adb root
adb shell avbctl disable-verification
adb reboot
# wait for sys.boot_completed, then:
adb root && adb remount
adb shell 'touch /system/x && echo WRITABLE'    # verify: "remount succeeded" alone lies
```

`adb remount` reports success even when `/` is still a read-only device-mapper device, so
always confirm with a real write.

**Docker cannot reach the emulator unaided.** The emulator binds adb to `127.0.0.1`, and
`host.docker.internal` resolves to IPv6 inside Docker Desktop, so forward a dual-stack port
to it and point MobSF at that:

```bash
node -e "const n=require('net');n.createServer(c=>{const u=n.connect(5555,'127.0.0.1');c.pipe(u);u.pipe(c);}).listen(5556)" &
# then set on the MobSF container:
MOBSF_ANALYZER_IDENTIFIER=host.docker.internal:5556
```

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
frida_instrument       -> e.g. defaultHooks="api_monitor,ssl_pinning_bypass"
test_activities        -> exercises activities (exportedOnly=true finds access-control bugs)
run_tls_tests          -> cleartext, certificate validation, pinning
get_logcat             -> secrets the app logs at runtime
stop_dynamic           -> collects logs, screenshots and app data
export_report type=combined  -> static + dynamic findings in one JSON file
```

**`frida_instrument` reports success even when Frida never attached.** MobSF answers
`{"status": "ok", "message": ""}` and logs the real failure only to its own log. On the
API 30 AVD used here, every call reported `ok` while MobSF's log showed
`frida.ServerNotRunningError: unable to connect to remote frida-server: closed` for each
one, and `apiMonitor` in the report stayed empty. So treat `ok` as "the request was
accepted" and confirm with `frida_logs`: `"Data does not exist."` means no hook ran.

To see the real error:

```bash
docker logs mobsf 2>&1 | grep -iE "frida|ServerNotRunning"
```

Instrument before exercising the app in any case — `frida_instrument` attaches to the
running process, and `test_activities` cycles the app through every activity, which
replaces it.

### Frida needs its server on the device, and MobSF may never put it there

MobSF pushes a frida-server binary to `/system/fd_server` during `mobsfy_device` and runs
it from there. If that push never happened, every Frida call fails while still reporting
`ok`.

The download is gated behind MobSF's own reachability check, which probes
**google.com and baidu.com**. On a network that blocks those but allows GitHub, the check
says "no internet" and MobSF never attempts the GitHub download that would have
succeeded. It logs one line and carries on:

```text
Android OS architecture identified as x86_64
Cannot download frida-server binary. You will need
frida-server-17.18.0-android-x86_64 in /home/mobsf/.MobSF/downloads/
MobSFying Completed!            <- reported success anyway
```

Confirm which situation you are in:

```bash
docker exec mobsf ls -la /home/mobsf/.MobSF/downloads/ | grep frida-server
adb shell ls -la /system/fd_server
```

To fix it, put the binary in the download directory yourself. MobSF skips the download
whenever the file is already there, so this needs no network access from MobSF at all.
Download `frida-server-<version>-android-<arch>.xz` from the
[frida releases](https://github.com/frida/frida/releases), decompress it, and copy it in
under the exact name MobSF's log asks for — the version must match the `frida` Python
package inside the container (`docker exec mobsf pip show frida`):

```bash
docker cp frida-server-17.18.0-android-x86_64 mobsf:/home/mobsf/.MobSF/downloads/
docker exec mobsf chmod 755 /home/mobsf/.MobSF/downloads/frida-server-17.18.0-android-x86_64
```

Then call `mobsfy_device` again and check that `/system/fd_server` exists. `/system` must
be writable for the push to land, so do this after the `avbctl` step above.

**Read the log before you stop.** MobSF attaches adb for the duration of the session, so
`get_logcat` after `stop_dynamic` has no device to read and reports
`- waiting for device -`.

## Troubleshooting
| Symptom | Cause and fix |
|---|---|
| `device_status` reports no device | `MOBSF_ANALYZER_IDENTIFIER` unset or wrong. Check `adb devices` on the host, and use `host.docker.internal` rather than `localhost` in Docker |
| MobSF reports a Frida or root error | The image is not rooted, or the system is not writable. Use a non-Play image, and start an AVD with `-writable-system` |
| `mobsfy_device` fails | MobSF cannot reach the device over adb. Check the port, and that the emulator is running |
| `mobsfy_device` says ok but Frida never works | MobSF could not download frida-server and said so only in its own log. See the Frida section above |
| HTTPS traffic is not captured | The app pins certificates. Use `frida_instrument` with `ssl_pinning_bypass` |
| `frida_logs` says "Data does not exist." | No hook ran. Frida did not attach (MobSF still reports `ok`) or the app is not running. Check `docker logs mobsf` for `ServerNotRunningError` |
| `get_logcat` says `- waiting for device -` | No live session. Call it between `start_dynamic` and `stop_dynamic` |
| Everything is empty after `stop_dynamic` | The app was not exercised. Use `test_activities` or `start_activity` between start and stop |

## Not available
- **Android screenshots on demand:** MobSF has no routed API for this (the view exists but is not exposed), so there is no screenshot tool. Screenshots are still collected by `test_activities` and appear in the dynamic report.
- **iOS dynamic analysis:** it needs Corellium or a jailbroken device. MobSF exposes the endpoints, but they are out of scope for now.
