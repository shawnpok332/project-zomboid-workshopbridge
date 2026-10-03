-- WorkshopBridge background-job polling + progress UI.
--
-- Java downloads/checks run on background threads; Lua polls job status on
-- tick so the game thread never blocks. Status table shape is defined in
-- docs/ARCHITECTURE.md ("Job status shape").
require "WorkshopBridge/WB_Config"
require "WorkshopBridge/WB_Json"

local activeJobs = {}       -- jobId -> { onUpdate=fn, onDone=fn }
local updateAvailable = {}  -- modId -> true (set by check jobs)
local wbTick = 0
local tickHooked = false

-- ---------- job tracking ----------

function WB_TrackJob(jobId, callbacks)
    if not jobId then return end
    activeJobs[jobId] = callbacks or {}
    WB_EnsurePolling()
end

function WB_MarkUpdateAvailable(modId)
    updateAvailable[modId] = true
end

function WB_UnmarkUpdateAvailable(modId)
    updateAvailable[modId] = nil
end

function WB_ClearUpdateAvailable()
    for k in pairs(updateAvailable) do updateAvailable[k] = nil end
end

function WB_IsUpdateAvailable(modId)
    return updateAvailable[modId] == true
end

function WB_CountUpdateAvailable()
    local n = 0
    for _ in pairs(updateAvailable) do n = n + 1 end
    return n
end

function WB_EnsurePolling()
    if not tickHooked then
        tickHooked = true
        Events.OnTick.Add(WB_PollJobs)
    end
end

function WB_PollJobs()
    wbTick = wbTick + 1
    for jobId, cb in pairs(activeJobs) do
        -- wbGetJobStatus returns a JSON string (see docs/ARCHITECTURE.md);
        -- decode it into a table here.
        local ok, s = pcall(wbGetJobStatus, jobId)
        local st = nil
        if ok and type(s) == "string" then
            local dok, dec = pcall(WB_JsonDecode, s)
            if dok then st = dec end
        end
        if type(st) == "table" then
            if cb.onUpdate then pcall(cb.onUpdate, st) end
            if st.state ~= "running" then
                activeJobs[jobId] = nil
                if cb.onDone then pcall(cb.onDone, st) end
            end
        else
            -- unknown job id or Java threw: drop it, report once
            activeJobs[jobId] = nil
            print("[WorkshopBridge] job " .. tostring(jobId) .. " failed: " .. tostring(st))
            if cb.onDone then pcall(cb.onDone, { state = "failed", error = tostring(st) }) end
        end
    end
    WB_TickProgressPanel(wbTick)
end

-- ---------- small UI helpers ----------

function WB_SetLabel(label, text)
    if not label then return end
    if label.setName then label:setName(text) else label.name = text end
end

function WB_SetButtonTitle(btn, text)
    if not btn then return end
    if btn.setTitle then btn:setTitle(text) else btn.title = text end
end

-- Shorten a job error for display: first line only, strip
-- "com.foo.BarException: " prefixes, trim, truncate. The full text stays
-- in the log (Java prints it), the UI only needs the gist.
function WB_ShortError(err, maxLen)
    maxLen = maxLen or 120
    local s = tostring(err or "?")
    s = s:match("^([^\n\r]*)") or s
    s = s:gsub("^[%w%.%$]+Exception:%s*", "")
    s = s:match("^%s*(.-)%s*$") or s
    if #s > maxLen then s = s:sub(1, maxLen - 3) .. "..." end
    return s
end

-- ---------- progress panel (throbber) ----------
--
-- A plain ISPanel, deliberately NOT a custom derived class: an earlier
-- version derived from ISPanel with its own initialise()/createChildren()
-- never rendered in-game. Root cause: PZ's ISUIElement:instantiate() calls
-- self:createChildren() itself, so our initialise() -> createChildren() ->
-- addChild() -> auto-instantiate() -> createChildren() recursion plus an
-- explicit instantiate() built the panel three times, discarding Java peers
-- along the way. This builds it once, the same way the working menu buttons
-- are built (new -> initialise -> addChild).

local progressPanel = nil
local progressBase = ""
local progressVisible = false
local flashHideAt = nil
local errorStuck = false

local function WB_EnsureProgressPanel(parent)
    if progressPanel then return progressPanel end
    local w, h = 380, 48
    progressPanel = ISPanel:new(
        math.max(0, parent:getWidth() / 2 - w / 2),
        math.max(0, parent:getHeight() / 2 - h / 2),
        w, h)
    progressPanel:initialise()
    progressPanel.backgroundColor = { r = 0.03, g = 0.03, b = 0.03, a = 0.93 }
    progressPanel.borderColor = { r = 0.45, g = 0.45, b = 0.45, a = 1.0 }
    -- click dismisses a stuck error (instance-level override; vanilla
    -- ISPanel:onMouseUp is a no-op unless moveWithMouse)
    progressPanel.onMouseUp = function(self, x, y)
        WB_HideProgress()
    end
    local label = ISLabel:new(12, 14, 20, "", 1, 1, 1, 1, UIFont.Small, true)
    label:initialise()
    progressPanel:addChild(label) -- auto-instantiates panel + label, no recursion
    progressPanel.wbLabel = label
    parent:addChild(progressPanel)
    return progressPanel
end

function WB_ShowProgress(parent, message)
    if not parent then return end
    local panel = WB_EnsureProgressPanel(parent)
    progressBase = message or ""
    flashHideAt = nil
    errorStuck = false
    WB_SetLabel(panel.wbLabel, progressBase)
    panel:setVisible(true)
    progressVisible = true
end

-- Show an error in the progress panel and keep it there until the user
-- clicks it away. Flash messages vanish after ~2.5s, too fast to read a
-- failure; errors need to wait for the user, not the other way round.
function WB_ShowError(parent, message)
    if not parent then return end
    WB_ShowProgress(parent, message)
    errorStuck = true
    flashHideAt = nil
    if progressPanel then
        WB_SetLabel(progressPanel.wbLabel, progressBase .. "  (click to dismiss)")
    end
end

-- Show a message briefly, then auto-hide (for errors).
function WB_FlashMessage(parent, message)
    WB_ShowProgress(parent, message)
    flashHideAt = wbTick + 150 -- ~2.5s at 60 ticks/s
end

function WB_TickProgressPanel(tick)
    if flashHideAt and tick >= flashHideAt then
        WB_HideProgress()
        return
    end
    if errorStuck then return end -- stuck error: no throbber dots, no auto-hide
    if progressVisible and progressPanel then
        local dots = string.rep(".", math.floor(tick / 20) % 4)
        WB_SetLabel(progressPanel.wbLabel, progressBase .. dots)
    end
end

function WB_HideProgress()
    flashHideAt = nil
    errorStuck = false
    progressVisible = false
    if progressPanel then progressPanel:setVisible(false) end
end
