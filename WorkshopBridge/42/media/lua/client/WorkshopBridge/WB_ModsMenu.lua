-- WorkshopBridge Mods-menu UI (skeleton - Phase 1).
--
-- B42 hook points (verified against PZ-Umbrella type stubs mirroring the vanilla
-- B42 Lua tree, plus the official ChooseGameInfo.Mod Javadocs):
--
--   * Screen: ModSelector (ISPanelJoypad subclass),
--     media/lua/client/OptionScreens/ModSelector/ModSelector.lua
--     Singleton: ModSelector.instance. Opened from the main menu via
--     MainScreen:onClickModList() (held in MainScreen.modSelect).
--   * "Update all" button: wrap ModSelector:create (runs when the screen builds),
--     anchor the new ISButton to self.backButton for positioning.
--   * Row status: rows are DRAWN, not widget-composed, by
--     ModSelector.ModListBox:doDrawItem(y, item, alt). `item` is a ModData table
--     and item.modId is the mod.info id= value. Wrap doDrawItem per-instance
--     (inside create()) to draw status text, e.g. "[Update available]".
--     A clickable per-row button would need manual hit-testing in onMouseDown,
--     so we deliberately do NOT put buttons in rows.
--   * Per-mod Update button: ModInfoPanel (right-hand details panel).
--     Wrap createChildren() once to add the ISButton + status label, and wrap
--     updateView(modInfo) to refresh per selected mod (modInfo:getId() gives the
--     mod id; ask the Java side for its workshop id, show "Unknown workshop ID"
--     when the map has no entry).
--   * Refresh: ModSelector:reloadMods() / ModSelector.Model:refreshMods() rescan
--     mods; updateView() repopulates the view.
--
-- UNCERTAIN (verify in-game, Phase 2): whether reloadMods() recreates the
-- ModListBox instance. If it does, re-apply the doDrawItem wrap from a wrapped
-- updateView()/reloadMods() as well. Also verify ModInfoPanel geometry at
-- runtime and position the Update button relative to existing children.
--
-- Bonus finding: the engine's Java ChooseGameInfo.Mod.getWorkshopID() exists,
-- but returns empty for non-Steam (steamcmd/GOG) mods -- the Java-side
-- workshopID -> [modID] map remains authoritative.
--
-- There is NO dedicated event for the Mods screen; method-wrapping is the
-- standard approach (client Lua loads at the main menu, so wrapping at file
-- scope works). Guard everything with wbUIAdded flags against double-hooking.
--
-- TODO(Phase 2): fill in the bodies below.

-- "Update all" button, added once per ModSelector instance.
local function WB_AddUpdateAllButton(modSelector)
    -- TODO(Phase 2):
    --   local btn = ISButton:new(x, y, w, h, getText("UI_WB_UpdateAll"), modSelector, WB_OnUpdateAll)
    --   position relative to modSelector.backButton; initialise(); instantiate(); addChild()
end

function WB_OnUpdateAll()
    -- TODO(Phase 2):
    --   local jobId = wbUpdateAll() -- via ZombieBuddy Java API
    --   WB_TrackJob(jobId, { onUpdate = ..., onDone = function() ModSelector.instance:reloadMods() end })
end

-- Per-instance wrap of the row-drawing function for status text.
local function WB_WrapRowDrawing(listBox)
    -- TODO(Phase 2):
    --   local _origDraw = listBox.doDrawItem
    --   listBox.doDrawItem = function(lb, y, item, alt)
    --       _origDraw(lb, y, item, alt)
    --       local workshopId = wbGetWorkshopId(item.modId)
    --       -- draw status text, e.g. "[Update available]" / "[Unknown workshop ID]"
    --   end
end

-- Per-mod Update button + status label, added once to the ModInfoPanel.
local function WB_AddModInfoPanelControls(modInfoPanel)
    -- TODO(Phase 2): create ISButton ("Update") + ISLabel (status) as children
end

-- Refresh per selected mod (called from wrapped ModInfoPanel:updateView).
local function WB_RefreshModInfoPanel(modInfoPanel, modInfo)
    -- TODO(Phase 2):
    --   local modId = modInfo:getId()
    --   local workshopId = wbGetWorkshopId(modId)
    --   if workshopId then show Update button -> wbUpdateMod(workshopId)
    --   else show grey "Unknown workshop ID" label end
end

-- Install all hooks. Called from WB_Main once the Java API is confirmed present.
function WB_HookModsMenu()
    -- TODO(Phase 2):
    --   wrap ModSelector.create -> WB_AddUpdateAllButton(self) + WB_WrapRowDrawing(self.modListPanel.modList)
    --   wrap ModInfoPanel.createChildren -> WB_AddModInfoPanelControls(self)
    --   wrap ModInfoPanel.updateView -> WB_RefreshModInfoPanel(self, modInfo)
    --   all with wbUIAdded guards
end
