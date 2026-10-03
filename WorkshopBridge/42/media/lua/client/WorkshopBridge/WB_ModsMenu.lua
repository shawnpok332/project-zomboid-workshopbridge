-- WorkshopBridge Mods-menu UI.
--
-- B42 hook points (verified against PZ-Umbrella type stubs + Javadocs):
--   * Screen: ModSelector (ISPanelJoypad), singleton ModSelector.instance,
--     opened from the main menu via MainScreen:onClickModList().
--   * "Check for updates" + "Update all": wrapped ModSelector:create, buttons
--     anchored to self.backButton (provisional placement - verify in-game).
--   * Row status text: rows are drawn (not widget-composed) by
--     ModListBox:doDrawItem(y, item, alt); item.modId is the mod.info id=.
--     Wrapped per-instance inside create().
--   * Per-mod Update button / status label: ModInfoPanel (createChildren once,
--     updateView(modInfo) per selection). Provisional placement - verify in-game.
-- There is no dedicated event for the Mods screen; method-wrapping is the
-- standard approach. All hooks are idempotent (wb*Added flags).
require "WorkshopBridge/WB_Config"
require "WorkshopBridge/WB_Jobs"
require "WorkshopBridge/WB_Download"

-- ---------- helpers ----------

-- mod id from whatever the row / updateView hands us (Lua table or Java object)
local function WB_GetModId(info)
    if type(info) == "table" and info.modId and info.modId ~= "" then
        return info.modId
    end
    if info and type(info.getId) == "function" then
        local ok, id = pcall(function() return info:getId() end)
        if ok and id and id ~= "" then return id end
    end
    return nil
end

-- the game's own workshop id signal (non-empty only for Steam-managed mods)
local function WB_GameWorkshopId(modInfo)
    if modInfo and type(modInfo.getWorkshopID) == "function" then
        local ok, id = pcall(function() return modInfo:getWorkshopID() end)
        if ok and id and id ~= "" then return id end
    end
    return nil
end

-- workshop id from OUR map via the Java API; nil = "Unknown workshop ID"
local function WB_WorkshopIdFor(modId)
    if type(wbGetWorkshopId) ~= "function" or not modId then return nil end
    local ok, wsid = pcall(wbGetWorkshopId, modId)
    if ok and wsid and wsid ~= "" then return wsid end
    return nil
end

-- ---------- button handlers ----------

local function WB_OnCheckAll(ms)
    if type(wbCheckForUpdates) ~= "function" then return end
    local ok, jobId = pcall(wbCheckForUpdates)
    if not ok or not jobId then
        WB_FlashMessage(ms, WB_Text.CheckFailed)
        return
    end
    print("[WorkshopBridge] check for updates started (job " .. tostring(jobId) .. ")")
    WB_TrackJob(jobId, {
        onUpdate = function(st)
            WB_ShowProgress(ms, st.message or WB_Text.Checking)
        end,
        onDone = function(st)
            WB_HideProgress()
            WB_ClearUpdateAvailable()
            local n = 0
            if st and st.state ~= "failed" and st.updates then
                for _, modId in ipairs(st.updates) do
                    WB_MarkUpdateAvailable(modId)
                    n = n + 1
                end
            end
            WB_RefreshUpdateAllButton(ms, n)
            if st and st.state == "failed" then
                print("[WorkshopBridge] check for updates failed: "
                    .. tostring(st.error or "?"))
                WB_ShowError(ms, WB_Text.CheckFailed .. ": " .. WB_ShortError(st.error, 64))
            else
                print("[WorkshopBridge] check for updates complete: "
                    .. n .. " update(s) available")
            end
        end,
    })
end

local function WB_OnUpdateAll(ms)
    if type(wbUpdateAll) ~= "function" then return end
    local ok, jobId = pcall(wbUpdateAll)
    if not ok or not jobId then
        WB_FlashMessage(ms, WB_Text.UpdateFailed)
        return
    end
    print("[WorkshopBridge] update-all started (job " .. tostring(jobId) .. ")")
    WB_TrackJob(jobId, {
        onUpdate = function(st)
            WB_ShowProgress(ms, st.message or WB_Text.Updating)
        end,
        onDone = function(st)
            WB_HideProgress()
            WB_ClearUpdateAvailable()
            WB_RefreshUpdateAllButton(ms, 0)
            if st and st.state == "failed" then
                print("[WorkshopBridge] update-all failed: " .. tostring(st.error or "?"))
                WB_ShowError(ms, WB_Text.UpdateFailed .. ": " .. WB_ShortError(st.error, 64))
            else
                print("[WorkshopBridge] update-all complete")
                -- rescan so newly downloaded/changed mods appear; then make
                -- sure our row wrap survived (re-applied defensively)
                if ms.reloadMods then pcall(function() ms:reloadMods() end) end
                WB_HookInstance(ms)
            end
        end,
    })
end

local function WB_OnModUpdate(panel)
    local modId = panel.wbModId
    local wsid = WB_WorkshopIdFor(modId)
    if not wsid or type(wbUpdateMod) ~= "function" then return end
    local ok, jobId = pcall(wbUpdateMod, wsid)
    if not ok or not jobId then
        WB_SetLabel(panel.wbStatusLabel, WB_Text.UpdateFailed)
        return
    end
    WB_SetLabel(panel.wbStatusLabel, WB_Text.Updating)
    print("[WorkshopBridge] updating " .. tostring(modId)
        .. " (workshop " .. tostring(wsid) .. ", job " .. tostring(jobId) .. ")")
    WB_TrackJob(jobId, {
        onUpdate = function(st)
            WB_SetLabel(panel.wbStatusLabel, st.message or WB_Text.Updating)
        end,
        onDone = function(st)
            if st and st.state == "failed" then
                print("[WorkshopBridge] update of " .. tostring(modId)
                    .. " failed: " .. tostring(st.error or "?"))
                WB_SetLabel(panel.wbStatusLabel,
                    WB_Text.UpdateFailed .. ": " .. WB_ShortError(st.error, 48))
            else
                print("[WorkshopBridge] update of " .. tostring(modId) .. " complete")
                WB_SetLabel(panel.wbStatusLabel, WB_Text.UpToDate)
                WB_UnmarkUpdateAvailable(modId)
            end
        end,
    })
end

-- ---------- ModSelector (screen) hooks ----------

function WB_RefreshUpdateAllButton(ms, n)
    if not ms or not ms.wbUpdateAllBtn then return end
    WB_SetButtonTitle(ms.wbUpdateAllBtn,
        n > 0 and string.format(WB_Text.UpdateAllN, n) or WB_Text.UpdateAll)
end

local function WB_AddMenuButtons(ms)
    if ms.wbButtonsAdded then return end
    ms.wbButtonsAdded = true
    -- build the progress panel up-front, in normal UI-construction context
    -- (lazy tick-time construction hid failures and poisoned the panel)
    WB_EnsureProgressPanel(ms)
    -- vanilla's action cluster is bottom-right (MapsOrder, ModsOrder, Accept),
    -- anchored right+bottom; ours join it on the left using the same pattern
    local anchor = ms.mapOrderbtn or ms.modOrderbtn or ms.acceptButton
    if not anchor then return end
    local bw, bh, gap = 150, anchor:getHeight(), 10
    local y = anchor:getY()
    local xUpdate = anchor:getX() - gap - bw
    local xCheck = xUpdate - gap - bw
    local xDownload = xCheck - gap - bw
    ms.wbCheckBtn = ISButton:new(xCheck, y, bw, bh, WB_Text.CheckForUpdates, ms,
        function() WB_OnCheckAll(ms) end)
    ms.wbUpdateAllBtn = ISButton:new(xUpdate, y, bw, bh, WB_Text.UpdateAll, ms,
        function() WB_OnUpdateAll(ms) end)
    ms.wbDownloadBtn = ISButton:new(xDownload, y, bw, bh, WB_Text.Download, ms,
        function() WB_ShowDownloadDialog(ms) end)
    for _, b in ipairs({ ms.wbCheckBtn, ms.wbUpdateAllBtn, ms.wbDownloadBtn }) do
        b:initialise()
        b:instantiate()
        b:setAnchorLeft(false)
        b:setAnchorRight(true)
        b:setAnchorTop(false)
        b:setAnchorBottom(true)
        b:setFont(UIFont.Small)
        b:ignoreWidthChange()
        b:ignoreHeightChange()
        ms:addChild(b)
    end
end

local function WB_WrapRowDrawing(ms)
    local panel = ms.modListPanel
    local list = panel and panel.modList
    if not list or list.wbRowWrapped then return end
    list.wbRowWrapped = true
    local _origDraw = list.doDrawItem
    if type(_origDraw) ~= "function" then return end
    list.doDrawItem = function(lb, y, item, alt)
        -- vanilla prerender does arithmetic on the return value
        -- (v.height = y2 - y), so it MUST be propagated
        local y2 = _origDraw(lb, y, item, alt)
        local modId = item and WB_GetModId(item)
        if modId and WB_IsUpdateAvailable(modId) then
            -- drawTextRight: right-aligned at x, no manual width measuring needed
            lb:drawTextRight(WB_Text.UpdateAvailableBadge, lb:getWidth() - 10, y,
                0.5, 1.0, 0.5, 1.0, UIFont.Small)
        end
        return y2
    end
end

-- Fallback job pump: the poll normally runs on Events.OnTick, but if the
-- tick doesn't fire while the Mods menu is open (main-menu context), the
-- screen's per-frame update() drives it instead. WB_PollJobs is idempotent
-- (terminal jobs are removed on first sighting), so double-pumping is
-- harmless.
local function WB_WrapUpdatePump(ms)
    if ms.wbUpdatePumped then return end
    ms.wbUpdatePumped = true
    local _update = ms.update
    if type(_update) ~= "function" then return end
    local pollErrorLogged = false
    ms.update = function(self, ...)
        local ok, err = pcall(WB_PollJobs)
        if not ok and not pollErrorLogged then
            pollErrorLogged = true
            print("[WorkshopBridge] poll error: " .. tostring(err))
        end
        return _update(self, ...)
    end
end

-- idempotent per-instance hook (safe to re-call, e.g. after reloadMods)
function WB_HookInstance(ms)
    if not ms then return end
    WB_AddMenuButtons(ms)
    WB_WrapRowDrawing(ms)
    WB_WrapUpdatePump(ms)
end

-- ---------- ModInfoPanel (per-mod) hooks ----------

local function WB_GetModInfoPanelClass()
    if ModInfoPanel then return ModInfoPanel end
    if ModSelector and ModSelector.ModInfoPanel then return ModSelector.ModInfoPanel end
    return nil
end

local function WB_AddModPanelControls(panel)
    if panel.wbControlsAdded then return end
    panel.wbControlsAdded = true
    -- provisional placement: bottom-left of the panel (verify in-game)
    local w, h = 130, 25
    local x = 10
    local y = math.max(40, panel:getHeight() - h - 10)
    panel.wbUpdateBtn = ISButton:new(x, y, w, h, WB_Text.Update, panel,
        function() WB_OnModUpdate(panel) end)
    panel.wbUpdateBtn:initialise()
    panel.wbUpdateBtn:instantiate()
    panel:addChild(panel.wbUpdateBtn)
    panel.wbStatusLabel = ISLabel:new(x, y - 22, 20, "", 0.8, 0.8, 0.8, 1, UIFont.Small, true)
    panel.wbStatusLabel:initialise()
    panel.wbStatusLabel:instantiate()
    panel:addChild(panel.wbStatusLabel)
end

local function WB_RefreshModPanel(panel, modInfo)
    local modId = WB_GetModId(modInfo)
    panel.wbModId = modId
    if not panel.wbUpdateBtn then return end
    local wsid = WB_WorkshopIdFor(modId)
    if wsid then
        panel.wbUpdateBtn:setVisible(true)
        WB_SetLabel(panel.wbStatusLabel,
            WB_IsUpdateAvailable(modId) and WB_Text.UpdateAvailableBadge or "")
    elseif WB_GameWorkshopId(modInfo) then
        panel.wbUpdateBtn:setVisible(false)
        WB_SetLabel(panel.wbStatusLabel, WB_Text.ManagedBySteam)
    else
        panel.wbUpdateBtn:setVisible(false)
        WB_SetLabel(panel.wbStatusLabel, WB_Text.UnknownWorkshopId)
    end
end

local function WB_HookModInfoPanel()
    local MIP = WB_GetModInfoPanelClass()
    if not MIP or MIP.wbHooked then return end
    MIP.wbHooked = true
    local _createChildren = MIP.createChildren
    MIP.createChildren = function(self)
        _createChildren(self)
        WB_AddModPanelControls(self)
    end
    local _updateView = MIP.updateView
    MIP.updateView = function(self, modInfo)
        _updateView(self, modInfo)
        WB_RefreshModPanel(self, modInfo)
    end
end

-- ---------- install ----------

-- Called from WB_Main once the Java API (or debug stub) is confirmed present.
function WB_HookModsMenu()
    if type(ModSelector) ~= "table" then
        print("[WorkshopBridge] WARN: ModSelector not found, menu hooks skipped")
        return
    end
    if not ModSelector.wbHooked then
        ModSelector.wbHooked = true
        local _create = ModSelector.create
        ModSelector.create = function(self)
            _create(self)
            WB_HookInstance(self)
        end
    end
    WB_HookModInfoPanel()
    -- if the screen already exists (re-entry), hook the live instance too
    if ModSelector.instance then
        pcall(function() WB_HookInstance(ModSelector.instance) end)
    end
end
