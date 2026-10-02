# Java backend build

## One-time setup

1. Download [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) and put
   `ZombieBuddy.jar` in `WorkshopBridge/java-src/libs/` (untracked by git).
2. Locate your B42 install's Java classes directory (the folder holding the
   game's compiled classes — e.g. `.../ProjectZomboid/Contents/Java` on macOS).

## Build

```bash
cd WorkshopBridge/java-src
gradle -PpzJavaDir="<path-to-game-java-dir>" installJar
```

This compiles against Java 17 and copies the result to
`WorkshopBridge/42/media/java/WorkshopBridge.jar`, matching `javaJarFile`
in `common/mod.info`.

Without `-PpzJavaDir` it looks in `libs/pz-java/` (also untracked).

## Layout (verified against a real B42 ZombieBuddy mod)

```
WorkshopBridge/
├── 42/media/java/WorkshopBridge.jar   <- built by installJar
├── 42/media/lua/client/WorkshopBridge/ <- Lua UI
└── common/mod.info                     <- require=ZombieBuddy (no backslash)
```

## Notes

- The Lua API is exposed as plain globals (`wbIsAvailable()` etc.) via
  `@LuaMethod(global = true)` — see `SteamCmdApi.java`.
- `wbGetJobStatus` returns a **JSON string**; Lua decodes it with the pure-Lua
  `WB_Json.lua` (Kahlua's Java return marshaling is not relied upon).
- `Main.main` only logs; the backend initializes lazily on the first Lua call.
