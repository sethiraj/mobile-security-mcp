# GraalVM native image (experimental, not yet verified)

**Why:** in stdio mode the MCP client starts the server for every session, and the JVM takes
about 15-20 seconds to boot on a cold machine. A native image starts in well under a second.
HTTP mode does not care, because the server is started once.

**Status: written but never built here.** No GraalVM toolchain was available, and Spring AI's
MCP annotation scanning plus Jackson reflection usually needs reachability hints before a
native image works. Treat the steps below as a starting point, not a finished feature.

## Building
The Spring Boot parent already manages the GraalVM plugin, so add a profile to `pom.xml`:

```xml
<profiles>
  <profile>
    <id>native</id>
    <build>
      <plugins>
        <plugin>
          <groupId>org.graalvm.buildtools</groupId>
          <artifactId>native-maven-plugin</artifactId>
          <configuration>
            <buildArgs>
              <buildArg>--initialize-at-build-time=org.slf4j</buildArg>
              <buildArg>-H:+ReportExceptionStackTraces</buildArg>
            </buildArgs>
          </configuration>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

With a GraalVM 21 JDK on the path:
```bash
./mvnw -Pnative native:compile -DskipTests
./target/mobsf-mcp --transport=stdio
```

Or through buildpacks, which need no local GraalVM:
```bash
./mvnw -Pnative spring-boot:build-image -DskipTests
```

## What to expect
- **Reflection:** the MCP tool and resource methods are found by reflection. Run the test
  suite with the tracing agent to collect hints:
  ```bash
  ./mvnw -B test -DargLine="-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image"
  ```
- **Jackson 3** serializes the report records; records generally work, but the generated
  schema code (victools) is test-only and must not be reachable from the image.
- **Logback** configuration is read at runtime; keep `logback-spring.xml` simple, or move to
  build-time initialization.

## Verifying it
Once a binary exists, the same checks as the jar apply:
```bash
./target/mobsf-mcp --transport=nonsense ; echo $?      # expect 2
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}' \
  | ./target/mobsf-mcp --transport=stdio                # expect a JSON-RPC reply on stdout only
```
Then measure startup, which is the entire point:
```bash
time (printf '%s\n' '…initialize…' | ./target/mobsf-mcp --transport=stdio > /dev/null)
```
