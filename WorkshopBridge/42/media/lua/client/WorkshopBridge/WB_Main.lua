-- WorkshopBridge entry point (client). Skeleton - Phase 1.
require "shared/WorkshopBridge/WB_Config"

-- TODO(Phase 2): detect the ZombieBuddy-exposed Java API. If the globals
-- (wbIsAvailable etc., see docs/ARCHITECTURE.md) are missing, the Mods menu
-- should show "ZombieBuddy required" guidance instead of Update buttons.

local function WB_OnInit()
    print("[WorkshopBridge] init (skeleton, Phase 1)")
    -- TODO(Phase 2): hook the Mods screen (see WB_ModsMenu.lua)
end

-- TODO(review): verify the best init event for menu-time Lua (OnGameBoot fires
-- at boot while client Lua is already live at the main menu).
Events.OnGameBoot.Add(WB_OnInit)
