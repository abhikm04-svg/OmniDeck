# Module template (OD-211)

The source for `./gradlew newModule`. Not a Gradle project, and deliberately not
compiled: the files here are `.template` because they are not valid Kotlin until the
placeholders are substituted, and a `.kt` extension would put them in front of
Spotless and the IDE for no benefit.

```bash
./gradlew newModule -Pid=fitness
./gradlew newModule -Pid=meal_planner -Powner=health-squad -Ptitle="Meal Planner"
```

`-Pid` is the module's short id and is **immutable once shipped** — it becomes the
package name, the Play split name, the route host and the on-device storage directory.
Lowercase letters, digits and underscores only.

## Placeholders

| Token | Becomes | Example for `-Pid=fitness` |
|---|---|---|
| `__SHORT_ID__` | the id as given | `fitness` |
| `__MODULE_ID__` | the full reverse-DNS id, and the package | `com.omnideck.fitness` |
| `__CLASS__` | PascalCase, for type names | `Fitness` |
| `__TITLE__` | display name (`-Ptitle`, else derived) | `Fitness` |
| `__OWNER__` | owning team (`-Powner`, else `unassigned`) | `health-squad` |

Directory names are substituted too, so `src/main/kotlin/__MODULE_ID__/` becomes
`src/main/kotlin/com/omnideck/fitness/`. A trailing `.template` is stripped.

## What the generated module already does

It compiles, its tests pass, and its tile appears on the home grid — with no edit to
`:app`, to `settings.gradle.kts`, or to anything else outside its own directory. That
is goal G1, and the plug-and-play fitness test (OD-212) checks it on every CI run.

If you find yourself needing to change a Shell file to make a module work, stop: that
is a gap in the SDK contract. Raise it as an SDK issue rather than editing the Shell,
because the next module will need the same thing.
