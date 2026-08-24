<div align="center">
<h1>BEAT Platform</h1>
<p><b>UI · Server · Agent for BEAT clusters</b></p>
</div>

## Modules

| Module | Role |
|--------|------|
| `beat-ui` | Web UI |
| `beat-server` | Manager API |
| `beat-agent` | Host agent |
| `beat-stack` | Service scripts (BEAT 3.3.0) |
| `beat-common` / `beat-dao` / `beat-grpc` / `beat-ai` / `beat-bom` | Shared libs |

## Parcels

Not stored here (too large). Use:

- https://github.com/tejs3/beat-repo3.0.0-1/releases
- or your lab `/ui/repo/` mirror

## Build

```bash
./mvnw clean package -DskipTests
```

```powershell
.\mvnw.cmd clean package -DskipTests
```

Requires **JDK 17+** and **Node.js** (UI).

## License

Apache-2.0. Derived from Apache Bigtop Manager; product name is **BEAT**.
