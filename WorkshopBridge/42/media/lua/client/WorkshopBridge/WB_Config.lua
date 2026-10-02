-- WorkshopBridge configuration + UI strings.
-- All Lua lives under media/lua/client/WorkshopBridge/ so requires stay simple.

WB_Config = {
    MOD_ID = "WorkshopBridge",
    STEAM_APP_ID = 108600, -- Project Zomboid

    -- When true and the ZombieBuddy Java API is NOT present, WB_Main installs
    -- WB_DebugStub, which fakes the Java API with canned responses. This lets
    -- you verify the whole UI in-game without building the Java side.
    -- Set to false for real use.
    DEBUG_STUB = true,
}

-- Hardcoded English strings, kept in one table so localization can be
-- layered on later without touching UI code.
WB_Text = {
    CheckForUpdates  = "Check for updates",
    UpdateAll        = "Update all",
    UpdateAllN       = "Update all (%d)",
    Update           = "Update",
    UnknownWorkshopId = "Unknown workshop ID",
    ManagedBySteam   = "Managed by Steam",
    UpdateAvailableBadge = "[Update available]",
    UpToDate         = "Up to date",
    Checking         = "Checking for updates...",
    Updating         = "Updating...",
    CheckFailed      = "Check failed",
    UpdateFailed     = "Update failed",
}
