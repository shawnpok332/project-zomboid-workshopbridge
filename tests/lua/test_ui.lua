-- UI integration test: loads the REAL WB_*.lua files with stubbed PZ globals
-- and exercises the full flow: boot -> hook -> check -> badge -> per-mod update.
local LUA_DIR = os.getenv("WB_LUA_DIR")
local failures = 0
local function check(cond, name, extra)
    if cond then print("PASS " .. name)
    else print("FAIL " .. name .. (extra and (" -- " .. tostring(extra)) or "")); failures = failures + 1 end
end

-- ---------- stub PZ environment ----------
Events = {}
Events.OnTick = { handlers = {} }
function Events.OnTick.Add(fn) table.insert(Events.OnTick.handlers, fn) end
Events.OnGameBoot = { handlers = {} }
function Events.OnGameBoot.Add(fn) table.insert(Events.OnGameBoot.handlers, fn) end
UIFont = { Small = "UIFont.Small" }

local UIElement = {}
UIElement.__index = UIElement
function UIElement:derive(name)
    local cls = setmetatable({}, self); cls.__index = cls; cls.__name = name
    return cls
end
function UIElement:new(x, y, w, h, ...)
    local o = setmetatable({ x = x or 0, y = y or 0, width = w or 0, height = h or 0,
        visible = true, children = {} }, self)
    o.initArgs = { ... }
    return o
end
function UIElement:initialise() end
function UIElement:instantiate() end
function UIElement:update() end
function UIElement:addChild(c) table.insert(self.children, c) end
function UIElement:setVisible(v) self.visible = v end
function UIElement:isVisible() return self.visible end
function UIElement:getWidth() return self.width end
function UIElement:getHeight() return self.height end
function UIElement:getX() return self.x end
function UIElement:getY() return self.y end
function UIElement:setName(n) self.name = n end
function UIElement:setTitle(t) self.title = t end
function UIElement:setFont(f) self.font = f end
function UIElement:setAnchorLeft(v) end
function UIElement:setAnchorRight(v) end
function UIElement:setAnchorTop(v) end
function UIElement:setAnchorBottom(v) end
function UIElement:ignoreWidthChange() end
function UIElement:ignoreHeightChange() end
function UIElement:drawText(...) end
function UIElement:drawTextRight(...) end

ISPanel = UIElement:derive("ISPanel")
ISLabel = UIElement:derive("ISLabel")
ISButton = UIElement:derive("ISButton")
function ISButton:new(x, y, w, h, title, clicktarget, onclick, ...)
    local o = UIElement.new(self, x, y, w, h)
    o.title, o.clicktarget, o.onclick = title, clicktarget, onclick
    return o
end

local badgesDrawn, rowsDrawn = {}, {}
local fakeList = {
    width = 600,
    doDrawItem = function(lb, y, item, alt)
        table.insert(rowsDrawn, { item = item })
        return y + 40 -- vanilla returns y + height; prerender does math on it
    end,
    getWidth = function(self) return self.width end,
    drawTextRight = function(self, text, x, y, r, g, b, a, font)
        table.insert(badgesDrawn, { text = text, x = x, y = y })
    end,
}
local fakeModListPanel = { modList = fakeList }
ModSelector = { instance = nil }
function ModSelector.create(self)
    self.backButton = ISButton:new(880, 710, 120, 30, "Back", self, function() end)
    self.mapOrderbtn = ISButton:new(700, 710, 100, 30, "MapsOrder", self, function() end)
    self.modListPanel = fakeModListPanel
end
function ModSelector:reloadMods() self.reloaded = (self.reloaded or 0) + 1 end

ModInfoPanel = UIElement:derive("ModInfoPanel")
function ModInfoPanel:createChildren() end
function ModInfoPanel:updateView(modInfo) self.lastModInfo = modInfo end

local function fakeModInfo(modId, workshopID)
    return {
        getId = function(self) return modId end,
        getWorkshopID = function(self) return workshopID or "" end,
        modId = modId, -- row items are Lua tables with .modId
    }
end

-- ---------- load the real mod files ----------
local tmp = os.getenv("TMPDIR") or "/tmp"
tmp = tmp .. "/wb-luatest"
os.execute("mkdir -p " .. tmp)
os.execute("ln -sfn " .. LUA_DIR .. " " .. tmp .. "/WorkshopBridge")
package.path = tmp .. "/?.lua;" .. package.path
require("WorkshopBridge/WB_Main")
for _, h in ipairs(Events.OnGameBoot.handlers) do h() end

local function tick(n)
    for _ = 1, n do
        for _, h in ipairs(Events.OnTick.handlers) do h() end
    end
end

-- ---------- boot ----------
check(WB_ApiKind == "stub", "boot installs debug stub")
check(type(wbIsAvailable) == "function" and wbIsAvailable(), "stub api available")

-- ---------- menu hook ----------
local ms = setmetatable({ x = 0, y = 0, width = 1024, height = 768, children = {} },
    { __index = UIElement })
function ms:reloadMods() self.reloaded = (self.reloaded or 0) + 1 end
ModSelector.create(ms)
check(ms.wbButtonsAdded, "menu buttons added on create")
check(ms.wbCheckBtn and ms.wbUpdateAllBtn, "check + update-all buttons exist")
check(#ms.children >= 2, "buttons added as children", #ms.children)
check(ms.wbUpdateAllBtn.title == "Update all", "update-all initial title")
-- progress panel is built eagerly at menu open, hidden until a job runs
check(ms.wbProgressPanel ~= nil and ms.wbProgressPanel.wbLabel ~= nil,
    "progress panel created eagerly with label")
check(not ms.wbProgressPanel:isVisible(), "progress panel hidden initially")

-- idempotent re-hook
local kids = #ms.children
WB_HookInstance(ms)
check(#ms.children == kids, "re-hook adds nothing")

-- ---------- per-mod panel: three states ----------
local panel = ModInfoPanel:new(0, 0, 400, 600)
panel:createChildren()
check(panel.wbControlsAdded, "mod panel controls added")
check(panel.wbUpdateBtn and panel.wbStatusLabel, "update button + status label exist")

panel:updateView(fakeModInfo("SomeMod", ""))
check(panel.wbModId == "SomeMod", "panel tracks mod id")
check(panel.wbUpdateBtn.visible, "update button visible for WB-known mod")
check(panel.wbStatusLabel.name == "", "no badge before check")

-- stub wbGetWorkshopId returns nil for our own MOD_ID -> falls to game signal
panel:updateView(fakeModInfo("WorkshopBridge", "999"))
check(not panel.wbUpdateBtn.visible, "button hidden for Steam-managed mod")
check(panel.wbStatusLabel.name == WB_Text.ManagedBySteam, "Managed by Steam label")

panel:updateView(fakeModInfo("NoMapMod", ""))
-- stub knows every mod except our own, so force unknown by removing it:
-- (uses a modId the stub hasn't "seen"; wbGetWorkshopId still returns the id...
-- so instead verify the unknown branch via a temporary override)
local realWsid = wbGetWorkshopId
wbGetWorkshopId = function() return nil end
panel:updateView(fakeModInfo("NoMapMod", ""))
check(panel.wbStatusLabel.name == WB_Text.UnknownWorkshopId, "Unknown workshop ID label")
wbGetWorkshopId = realWsid

-- ---------- check-for-updates flow ----------
panel:updateView(fakeModInfo("SomeMod", "")) -- re-select; marks seenModIds
ms.wbCheckBtn.onclick() -- click "Check for updates"
tick(30)
-- progress panel: the screen's own panel, visible, with a label
local prog = ms.wbProgressPanel
check(prog ~= nil and prog.wbLabel ~= nil, "progress panel present with label")
check(prog:isVisible(), "progress panel visible during job")
check(prog.wbLabel.name:find("Checking") ~= nil, "progress shows check message",
    prog.wbLabel.name)
tick(200) -- stub check job: 12 ticks/step x 6 steps
check(WB_IsUpdateAvailable("SomeMod"), "update marked available after check")
check(ms.wbUpdateAllBtn.title == "Update all (2)", "update-all button shows count",
    ms.wbUpdateAllBtn.title)

-- row badge
rowsDrawn, badgesDrawn = {}, {}
local retY = fakeList:doDrawItem(100, fakeModInfo("SomeMod", ""), false)
check(retY == 140, "wrapper propagates doDrawItem return value", retY)
check(#rowsDrawn == 1 and #badgesDrawn == 1, "badge drawn for update-available mod")
check(badgesDrawn[1] and badgesDrawn[1].text == WB_Text.UpdateAvailableBadge, "badge text")
rowsDrawn, badgesDrawn = {}, {}
fakeList:doDrawItem(120, fakeModInfo("OtherMod", ""), false)
check(#rowsDrawn == 1 and #badgesDrawn == 0, "no badge for up-to-date mod")

-- ---------- per-mod update flow ----------
panel:updateView(fakeModInfo("SomeMod", ""))
check(panel.wbStatusLabel.name == WB_Text.UpdateAvailableBadge, "panel shows badge pre-update")
check(panel.wbUpdateBtn.title == WB_Text.Update, "button says Update when update available",
    panel.wbUpdateBtn.title)
panel.wbUpdateBtn.onclick() -- click per-mod Update
tick(30)
tick(200) -- stub update job: 12 ticks/step x 8 steps
check(panel.wbStatusLabel.name == WB_Text.UpToDate, "panel shows Up to date after update",
    panel.wbStatusLabel.name)
check(not WB_IsUpdateAvailable("SomeMod"), "update-available cleared after update")
check(panel.wbUpdateBtn.title == WB_Text.ForceUpdate, "button says Force update when up to date",
    panel.wbUpdateBtn.title)

-- ---------- update-all flow ----------
ms.wbCheckBtn.onclick()
tick(250)
check(WB_CountUpdateAvailable() >= 1, "check re-marks updates")
ms.wbUpdateAllBtn.onclick()
tick(250)
check(WB_CountUpdateAvailable() == 0, "update-all clears marks")
check(ms.wbUpdateAllBtn.title == "Update all", "update-all title reset")
check((ms.reloaded or 0) >= 1, "reloadMods called after update-all")

-- ---------- check flashes its result summary ----------
panel:updateView(fakeModInfo("SomeMod", "")) -- select; no updates marked now
check(panel.wbUpdateBtn.title == WB_Text.ForceUpdate, "button neutral before check")
ms.wbCheckBtn.onclick()
tick(80) -- stub check job completes (~72 ticks)
local sumPanel = ms.wbProgressPanel
check(sumPanel:isVisible(), "check result flashed")
check(sumPanel.wbLabel.name == "Check complete", "flash shows check summary",
    sumPanel.wbLabel.name)
-- visible panel refreshed without reselecting: badge + Update title
check(panel.wbStatusLabel.name == WB_Text.UpdateAvailableBadge,
    "panel badge refreshed by check")
check(panel.wbUpdateBtn.title == WB_Text.Update,
    "button title refreshed by check", panel.wbUpdateBtn.title)
tick(200) -- flash timeout expires
check(not sumPanel:isVisible(), "result flash auto-hides")

-- ---------- unknown job is dropped gracefully ----------
local doneState = nil
WB_TrackJob("no-such-job", { onDone = function(st) doneState = st end })
tick(2)
check(doneState and doneState.state == "failed", "unknown job -> failed onDone")

-- ---------- flash message auto-hides, error panel sticks ----------
WB_FlashMessage(ms, "boom")
local flashPanel = ms.wbProgressPanel
check(flashPanel:isVisible(), "flash panel visible")
check(flashPanel.wbLabel.name == "boom", "flash shows message", flashPanel.wbLabel.name)
tick(200)
-- progress panel should have been hidden by the flash timeout
check(not flashPanel:isVisible(), "flash auto-hides after timeout")

WB_ShowError(ms, "kaput")
local errPanel = ms.wbProgressPanel
check(errPanel:isVisible(), "error panel visible")
check(errPanel.wbLabel.name:find("kaput") ~= nil
    and errPanel.wbLabel.name:find("click to dismiss") ~= nil,
    "error shows message + dismiss hint", errPanel.wbLabel.name)
tick(300)
check(errPanel:isVisible(), "error panel sticks (no auto-hide)")
errPanel:onMouseUp(errPanel, 10, 10) -- click dismisses
check(not errPanel:isVisible(), "click dismisses error panel")

-- ---------- update() fallback pump ----------
check(ms.wbUpdatePumped, "update() pump installed on menu instance")
-- simulate a dead tick (no OnTick firing): drive jobs via ms:update() only
local fbDone, fbUpdates = nil, 0
local realStatus = wbGetJobStatus
local fbTicks = 0
wbGetJobStatus = function(jobId)
    fbTicks = fbTicks + 1
    if fbTicks < 3 then
        return '{"state":"running","done":0,"total":1,"message":"Working"}'
    end
    return '{"state":"done","done":1,"total":1,"message":"Done"}'
end
WB_TrackJob("fallback-job", {
    onUpdate = function(st) fbUpdates = fbUpdates + 1 end,
    onDone = function(st) fbDone = st end,
})
for _ = 1, 5 do ms:update() end -- no tick() calls: OnTick stays silent
check(fbUpdates >= 1, "fallback pump delivers onUpdate", fbUpdates)
check(fbDone and fbDone.state == "done", "fallback pump delivers onDone")
wbGetJobStatus = realStatus

print(failures == 0 and "ALL UI TESTS PASSED" or (failures .. " FAILURES"))
os.exit(failures == 0 and 0 or 1)
