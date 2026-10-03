-- WorkshopBridge entry point (client).
require "WorkshopBridge/WB_Config"
require "WorkshopBridge/WB_Jobs"
require "WorkshopBridge/WB_ModsMenu"

-- "java" | "stub" | nil
WB_ApiKind = nil

local function WB_DetectApi()
    -- real ZombieBuddy-exposed Java API?
    if type(wbIsAvailable) == "function" then
        local ok, res = pcall(wbIsAvailable)
        if ok and res then return "java" end
    end
    -- Note: on PZ 42.21.0 the API is absent even with ZB installed, because
    -- ZB 2.3.2's loadMods hook misses the new List<String> signature
    -- (zed-0xff/ZombieBuddy#53, fixed by PR #56). The stub fallback below
    -- keeps the UI testable until then; it is not a substitute for the
    -- real backend.
    -- fall back to the canned debug stub so the UI stays verifiable in-game
    -- when DEBUG_STUB is explicitly enabled (it defaults to false: a shipped
    -- build must never report fake successes)
    if WB_Config.DEBUG_STUB then
        require "WorkshopBridge/WB_DebugStub"
        if WB_InstallDebugStub() then return "stub" end
    end
    return nil
end

local function WB_Init()
    WB_ApiKind = WB_DetectApi()
    if not WB_ApiKind then
        print("[WorkshopBridge] ZombieBuddy Java API not found and DEBUG_STUB is off.")
        print("[WorkshopBridge] Install ZombieBuddy (see README), then enable this mod.")
        return
    end
    WB_HookModsMenu()
    print("[WorkshopBridge] initialised (api=" .. WB_ApiKind .. ")")
end

-- client Lua loads at the main menu, before any Mods screen exists, so
-- deferring to OnGameBoot is safe; fall back to immediate init if missing.
if Events.OnGameBoot then
    Events.OnGameBoot.Add(WB_Init)
else
    WB_Init()
end
