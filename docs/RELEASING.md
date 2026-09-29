# Releasing

Publishing is deliberately manual: it puts an image under **your** name on a public
registry. Nothing here runs by itself.

## Once, before the first release

1. **Namespace: already set to `sure4docker`** (repository `sure4docker/mobsf-mcp`, image
   `sure4docker/mobsf-mcp`). To move it somewhere else later:
   ```bash
   scripts/set-namespace.sh <new-owner> "Your Name or Company"   # rewrites the old owner
   ```
   That script updates the README, `server.json`, the Docker labels, the compose default, the
   catalog entry, the Action defaults and the MIT copyright line. Check the MIT copyright in
   `LICENSE`: it currently says "sure4docker", which you may want to be a personal or company
   name instead.

2. **Re-check the market.** This was never possible from the build machine, which cannot
   reach GitHub. Search GitHub, `registry.modelcontextprotocol.io`, the Docker MCP Catalog
   and Smithery for "mobsf mcp". If an actively maintained server already covers this,
   contributing there beats publishing another one.

3. **Create the GitHub repository** and push the code. The image labels, `server.json` and
   the README all point at it, and the release workflow lives there.

4. **Add the repository secrets** `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` (a Docker Hub
   *access token*, not your password: Docker Hub → Account settings → Personal access
   tokens, scope "Read & Write"). GHCR needs nothing extra.

5. **Create the Docker Hub repository** `<user>/mobsf-mcp` and paste the README's summary
   into its description.

## Each release

1. Update `CHANGELOG.md` (move items out of *Unreleased*) and set the version in `pom.xml`.
   The workflow refuses to publish when the tag and `pom.xml` disagree.
2. Check locally:
   ```bash
   ./mvnw -B verify
   docker compose up -d --build && bash scripts/smoke-test.sh <an-app>.apk
   ```
3. Tag and push:
   ```bash
   git tag v1.0.0-beta.1 && git push origin v1.0.0-beta.1
   ```
   The `Release` workflow then runs the tests, builds `linux/amd64` and `linux/arm64`,
   pushes to Docker Hub and GHCR, signs both with cosign (keyless), attaches an SBOM and
   provenance, and drafts the GitHub release.

To rehearse without publishing, run the workflow manually (**Actions → Release → Run
workflow**): it builds both architectures and pushes nothing.

## Publishing without GitHub Actions
```bash
docker login
docker buildx build --platform linux/amd64,linux/arm64 \
  --build-arg VERSION=1.0.0-beta.1 --build-arg SOURCE_URL=https://github.com/<owner>/mobsf-mcp \
  -t <user>/mobsf-mcp:1.0.0-beta.1 -t <user>/mobsf-mcp:latest --push .
```
Signing by hand needs a cosign key (`cosign generate-key-pair`, then
`cosign sign --key cosign.key <user>/mobsf-mcp:1.0.0-beta.1`); the workflow's keyless signing only
works inside GitHub Actions.

## Listing the server
- **Official MCP registry:** publish `server.json` with the `mcp-publisher` CLI
  (`mcp-publisher login github` then `mcp-publisher publish`). The name must be
  `io.github.<owner>/mobsf-mcp` and must match the repository you own.
- **Docker MCP Catalog:** copy `docs/docker-mcp-catalog-entry.yaml` to
  `servers/mobsf-mcp/server.yaml` in a fork of `docker/mcp-registry` and open a pull
  request. Docker builds and signs the image itself, so this works even if you never push
  your own image.
- **Smithery:** optional; it reads the repository directly.

## After publishing
Check the image on a clean machine:
```bash
docker run --rm <user>/mobsf-mcp:1.0.0-beta.1 --transport=nonsense   # should exit 2 with a clear error
docker run -d -p 127.0.0.1:8080:8080 -e MOBSF_URL=... -e MOBSF_API_KEY=... -e MCP_AUTH_TOKEN=... <user>/mobsf-mcp:1.0.0-beta.1
curl -fsS http://localhost:8080/actuator/health
```
And verify the signature with the `cosign verify` command printed in the release notes.
