# Network and Privacy

KMP Dependency Resolver is a client-only IDE plugin. It has no account, backend service, analytics, advertising, or telemetry.

## Network destinations

The plugin connects only to these fixed HTTPS hosts:

| Host | Purpose | Data sent |
| --- | --- | --- |
| `api.klibs.io` | KMP/CMP library search and target metadata | Search query and selected target filters |
| `search.maven.org` | Maven Central coordinate and version search | Search query |
| `repo1.maven.org` | Maven metadata and publication evidence | Selected public dependency coordinates and versions |
| `dl.google.com` | Google Maven indexes and publication metadata | Search query and selected public dependency coordinates |
| `plugins.gradle.org` | Plugin recipe metadata referenced by reviewed recipes | Selected public plugin IDs and versions |
| `raw.githubusercontent.com` | Fixed, signed recipe manifest | No project data |
| `github.com` | Immutable, digest-verified recipe catalog release asset | No project data |

Provider switches are available under **Settings/Preferences → Tools → KMP Dependency Resolver**. Queries and target filters go only to enabled search providers. Offline mode makes no network requests. Endpoint URLs are fixed and cannot be replaced with arbitrary hosts in the MVP.

## Data that never leaves the IDE

The plugin does not transmit project names or paths, source code, build files, version catalogs, installed dependency lists, search history, or the changes shown in a preview. Project inspection and dependency edits happen locally.

## Local storage

Search responses and the last valid signed recipe catalog are stored below the IDE system directory in `kmp-dependency-resolver/cache`. The cache contains public search/recipe data, not source files. Use **Clear Cache…** in plugin settings to remove it. Invalid or partially downloaded recipe catalogs never replace the last valid local catalog.

## Remote recipe integrity

Remote recipes are optional. The plugin fetches the manifest only from:

`https://raw.githubusercontent.com/itsSiddharthGupta/KmpDependencyResolver/main/registry/manifest.json`

The manifest must have a valid Ed25519 signature from the public key bundled with the plugin. Its catalog URL must be an immutable release asset below `https://github.com/itsSiddharthGupta/KmpDependencyResolver/releases/download/`. The plugin also verifies the declared schema, maximum size, exact byte length, and SHA-256 digest before atomically installing a catalog. Any failure keeps the bundled or last-good catalog and reports a nonblocking stale-catalog status.
