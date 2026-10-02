# Java backend build notes (skeleton)

The Java side is built in **Phase 3** — this directory currently holds stubs only.

Planned setup (per the [ZombieBuddy ModdingGuide](https://github.com/zed-0xff/ZombieBuddy/blob/HEAD/doc/ModdingGuide.md)):

1. Put `ZombieBuddy.jar` in `WorkshopBridge/java-src/libs/` (untracked by git — download it yourself).
2. Add the PZ game classes as a `compileOnly` dependency (see the ModdingGuide).
3. Verify the Java toolchain version against your B42 install (research noted drift between Java 17 and JDK 25).
4. `./gradlew build` → output JAR goes to `WorkshopBridge/42/media/java/WorkshopBridge.jar`
   (path must match `javaJarFile` in `WorkshopBridge/mod.info` — verify in Phase 1 review).
