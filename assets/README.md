# Brand assets

The mark is a shield (security) over a phone (mobile) with a scan line and a connector plug
(MCP). Drawn for this project, so there is nothing third-party to license. It is deliberately
unlike MobSF's own logo: this project is not affiliated with MobSF.

| File | Size | Use |
|---|---|---|
| `logo.svg` | vector | The source. Edit this and regenerate the PNGs |
| `logo-512.png` | 512×512 | Docker Hub repository image, GitHub social preview, README on sites that reject SVG |
| `logo-256.png` | 256×256 | Docker MCP Catalog icon (referenced by `docs/docker-mcp-catalog-entry.yaml`) |
| `logo-128.png` | 128×128 | Listings and cards |
| `logo-64.png` | 64×64 | Small inline use |
| `favicon-32.png` | 32×32 | Favicon |

All PNGs have an alpha channel, so they sit on light or dark backgrounds.

## Regenerating the PNGs
After editing `logo.svg`:

```bash
for size in 512 256 128 64; do
  npx --yes sharp-cli@5 --input assets/logo.svg --output "assets/logo-${size}.png" resize $size $size
done
npx --yes sharp-cli@5 --input assets/logo.svg --output assets/favicon-32.png resize 32 32
```

`sharp` renders SVG directly, so no ImageMagick or Inkscape is needed.

## Colours
| Token | Hex | Where |
|---|---|---|
| Shield, light | `#3fa9f5` | Gradient top |
| Shield, dark | `#1b5e9c` | Gradient bottom |
| Outline | `#0d3a63` | Strokes and screen content |
| Scan line | `#18c98a` / `#7ef2c2` | Scan highlight |
| Screen | `#f7fbff` | Phone body |
