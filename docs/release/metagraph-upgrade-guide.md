# Metagraph Upgrade Guide: Tessellation v4.0.0

This guide covers upgrading metagraphs from Tessellation v3.x to v4.0.0.

---

## Quick Reference: File Changes

| File | Change |
|------|--------|
| `project/build.properties` | `sbt.version=1.8.0` -> `sbt.version=1.9.8` |
| `build.sbt` | `scalaVersion := "2.13.10"` -> `scalaVersion := "2.13.18"` |
| `build.sbt` | Add OSGI-INF merge strategy (see below) |
| `project/Dependencies.scala` | `tessellation = "3.x.x"` -> `tessellation = "4.0.0-rc.10"` |
| `project/Dependencies.scala` | `kind-projector` `0.13.2` -> `0.13.4` |
| `project/Dependencies.scala` | `semanticdb-scalac` `4.7.1` -> `4.14.2` |

---

## Required: OSGI-INF Merge Strategy

BouncyCastle JARs in newer versions include OSGI-INF metadata that conflicts during assembly. Add this to your `build.sbt`:

```scala
assembly / assemblyMergeStrategy := {
  case "META-INF/io.netty.versions.properties" => MergeStrategy.first
  case "META-INF/versions/9/module-info.class" => MergeStrategy.first
  case PathList("META-INF", "versions", _, "OSGI-INF", _ @_*)    => MergeStrategy.discard
  case PathList(xs@_*) if xs.last == "module-info.class" => MergeStrategy.first
  case x if x.endsWith("/module-info.class") => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}
```

---

## Required: Java 21

Tessellation v4 requires **Java 21** (up from Java 11).

### Code Changes

The `URL(String)` constructor is deprecated in Java 21. Replace:

```scala
// Before
new URL("https://example.com/path")

// After
URI.create("https://example.com/path").toURL
```

### Runtime

Ensure your build and runtime environments use JDK 21:

```bash
# Using SDKMAN
sdk install java 21.0.2-tem
sdk use java 21.0.2-tem

# Using Coursier
eval "$(cs java --jvm adoptium:21 --env)"
```

### Docker Images

Update base images in your Dockerfiles:

```dockerfile
# Before
FROM eclipse-temurin:11-jre

# After
FROM eclipse-temurin:21-jre
```

---

## MPT State Proofs

Tessellation v4 introduces Merkle Patricia Trie (MPT) based state proofs for global snapshots.

### Impact on Metagraphs

**No code changes required** - MPT is handled at the global L0 layer. Metagraph snapshots continue to use the existing proof format (`CurrencyStateProofSelector` always returns `LegacyFormat`).

### For Validators

State proof validation is performed during snapshot acceptance. Expect:
- Slightly increased CPU usage during snapshot processing
- New log messages related to `StateProofValidator` and `MptStore`

---

## Migration Checklist

- [ ] Update `project/build.properties` - sbt 1.9.8
- [ ] Update `build.sbt` - Scala 2.13.18
- [ ] Update `build.sbt` - Add OSGI-INF merge strategy
- [ ] Update `project/Dependencies.scala` - tessellation version to `4.0.0-rc.10`
- [ ] Update `project/Dependencies.scala` - kind-projector 0.13.4
- [ ] Update `project/Dependencies.scala` - semanticdb-scalac 4.14.2
- [ ] Search codebase for `new URL(` and update to `URI.create().toURL`
- [ ] Update CI/CD to use Java 21
- [ ] Update Docker base images to Java 21
- [ ] Run full test suite
- [ ] Test with local cluster before network deployment

---

## Compatibility Notes

- **Euclid SDK:** Check for updated SDK version compatible with v4
- **Existing Snapshots:** Backward compatible - existing snapshot data remains valid
- **Metagraph Framework:** No breaking changes to metagraph lifecycle APIs

---

## Troubleshooting

### Assembly Fails with OSGI-INF Conflict

```
[error] deduplicate: different file contents found in the following:
[error] .../bcprov-jdk18on-1.78.1.jar:OSGI-INF/bundle.info
```

**Fix:** Add the OSGI-INF merge strategy shown above.

### StackOverflowError During Compilation

Shapeless macro expansion can exceed default stack size.

**Fix:**
```bash
export SBT_OPTS="-Xss8m"
sbt compile
```

### URL Constructor Deprecation Warning

```
warning: constructor URL in class URL is deprecated
```

**Fix:** Replace `new URL(s)` with `URI.create(s).toURL`
