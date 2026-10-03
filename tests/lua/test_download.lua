-- Download-dialog test: the WB_ParseWorkshopId parser plus the full
-- dialog flow (open -> validate -> download job -> reloadMods), using the
-- real WB_*.lua files with stubbed PZ globals.
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

ISPanel = UIElement:derive("ISPanel")
ISLabel = UIElement:derive("ISLabel")
ISButton = UIElement:derive("ISButton")
function ISButton:new(x, y, w, h, title, clicktarget, onclick, ...)
    local o = UIElement.new(self, x, y, w, h)
    o.title, o.clicktarget, o.onclick = title, clicktarget, onclick
    return o
end
ISTextEntryBox = UIElement:derive("ISTextEntryBox")
function ISTextEntryBox:new(text, x, y, w, h)
    local o = UIElement.new(self, x, y, w, h)
    o.text = text or ""
    return o
end
function ISTextEntryBox:getText() return self.text end
function ISTextEntryBox:setText(t) self.text = t end

local fakeList = {
    width = 600,
    doDrawItem = function(lb, y, item, alt) return y + 40 end,
    getWidth = function(self) return self.width end,
    drawTextRight = function(self, ...) end,
}
ModSelector = { instance = nil }
function ModSelector.create(self)
    self.backButton = ISButton:new(880, 710, 120, 30, "Back", self, function() end)
    self.mapOrderbtn = ISButton:new(700, 710, 100, 30, "MapsOrder", self, function() end)
    self.modListPanel = { modList = fakeList }
end

-- ---------- load the real mod files ----------
local tmp = (os.getenv("TMPDIR") or "/tmp") .. "/wb-luatest-dl"
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

check(WB_ApiKind == "stub", "boot installs debug stub")

-- ---------- parser unit tests ----------
check(WB_ParseWorkshopId("2685600088") == "2685600088", "parse bare id")
check(WB_ParseWorkshopId("  2685600088  ") == "2685600088", "parse id with whitespace")
check(WB_ParseWorkshopId("id=2685600088") == "2685600088", "parse id= form")
check(WB_ParseWorkshopId("https://steamcommunity.com/sharedfiles/filedetails/?id=2685600088")
    == "2685600088", "parse full URL")
check(WB_ParseWorkshopId("https://steamcommunity.com/sharedfiles/filedetails/?id=2685600088&searchtext=foo")
    == "2685600088", "parse URL with extra params")
check(WB_ParseWorkshopId("") == nil, "reject empty")
check(WB_ParseWorkshopId("   ") == nil, "reject whitespace")
check(WB_ParseWorkshopId("abc") == nil, "reject text")
check(WB_ParseWorkshopId("12ab34") == nil, "reject mixed")
check(WB_ParseWorkshopId("https://steamcommunity.com/sharedfiles/filedetails/")
    == nil, "reject URL without id")
check(WB_ParseWorkshopId(nil) == nil, "reject nil")
check(WB_ParseWorkshopId(12345) == nil, "reject non-string")

-- ---------- dialog flow ----------
local ms = setmetatable({ x = 0, y = 0, width = 1024, height = 768, children = {} },
    { __index = UIElement })
function ms:reloadMods() self.reloaded = (self.reloaded or 0) + 1 end
ModSelector.create(ms)
check(ms.wbDownloadBtn and ms.wbDownloadBtn.title == WB_Text.Download,
    "download button added to menu")

local kidsBefore = #ms.children
ms.wbDownloadBtn.onclick()
check(ms.wbDownloadDialog ~= nil, "dialog opens on click")
check(ms.wbDownloadDialog:isVisible(), "dialog visible")
local dlg = ms.wbDownloadDialog
ms.wbDownloadBtn.onclick() -- again: must not stack duplicates
check(#ms.children == kidsBefore + 1, "no duplicate dialogs", #ms.children)
check(#dlg.children == 6, "dialog builds its controls exactly once", #dlg.children)

-- invalid input: error shown, dialog stays open, no job started
dlg.entry:setText("not a workshop id")
dlg.downloadBtn.onclick()
check(dlg.errorLabel.name == WB_Text.InvalidWorkshopId, "invalid input shows error",
    dlg.errorLabel.name)
check(ms.wbDownloadDialog == dlg, "dialog stays open on invalid input")

dlg.entry:setText("")
dlg.downloadBtn.onclick()
check(dlg.errorLabel.name == WB_Text.InvalidWorkshopId, "empty input shows error")

-- valid id: dialog closes, job runs, reloadMods called on completion
dlg.entry:setText("  777888999  ")
dlg.downloadBtn.onclick()
check(ms.wbDownloadDialog == nil, "dialog closes on valid input")
tick(200) -- stub update job: 12 ticks/step x 8 steps
check((ms.reloaded or 0) >= 1, "reloadMods called after download")

-- URL input works too
ms.wbDownloadBtn.onclick()
local dlg2 = ms.wbDownloadDialog
dlg2.entry:setText("https://steamcommunity.com/sharedfiles/filedetails/?id=555666777")
dlg2.downloadBtn.onclick()
check(ms.wbDownloadDialog == nil, "dialog closes on URL input")
tick(200)
check((ms.reloaded or 0) >= 2, "reloadMods called after URL download")

-- cancel closes without starting anything
ms.wbDownloadBtn.onclick()
local dlg3 = ms.wbDownloadDialog
check(dlg3 ~= nil, "dialog reopens")
dlg3.cancelBtn.onclick()
check(ms.wbDownloadDialog == nil, "cancel closes dialog")

print(failures == 0 and "ALL DOWNLOAD TESTS PASSED" or (failures .. " FAILURES"))
os.exit(failures == 0 and 0 or 1)
