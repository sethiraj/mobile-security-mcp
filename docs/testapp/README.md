# Test app for the smoke test

A deliberately insecure minimal Android source project. MobSF scans it as a source-code
zip, so no Android SDK is needed to produce a test input. It contains cleartext traffic,
`debuggable=true`, `allowBackup=true`, an exported activity, a hardcoded API key and
password, `Random` used as a token source, DES/ECB encryption, world-readable preferences
and sensitive logging.

Build the zip with the JDK's `jar` tool, not PowerShell's `Compress-Archive`: MobSF cannot
read archives whose entries use backslash separators.

```bash
mkdir -p testapp/app/src/main/java/com/example/vuln
cp docs/testapp/AndroidManifest.xml testapp/app/src/main/
cp docs/testapp/MainActivity.java testapp/app/src/main/java/com/example/vuln/
(cd testapp && jar cfM ../apks/vulndemo-src.zip .)
```

For a realistic APK use a published vulnerable app such as DIVA or InsecureBankv2.
