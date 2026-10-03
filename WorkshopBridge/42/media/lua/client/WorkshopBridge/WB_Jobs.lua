-- WorkshopBridge background-job polling + progress UI.
--
-- Java downloads/checks run on background threads; Lua polls job status on
-- tick so the game thread never blocks. Status table shape is defined in
-- docs/ARCHITECTURE.md ("Job status shape").
require "WorkshopBridge/WB_Config"
require "WorkshopBridge/WB_Json"

local activeJobs = {}       -- jobId -> { onUpdate=fn, onDone=fn }
local lastJobState = {}     -- jobId -> last seen state (for transition logging)
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
        print("[WorkshopBridge] job polling started")
    end
end

function WB_PollJobs()
    wbTick = wbTick + 1
    -- Periodic heartbeat while jobs are in flight: shows the poll is alive
    -- and what it sees. Silent when idle.
    if wbTick % 600 == 1 then
        local n = 0
        for _ in pairs(activeJobs) do n = n + 1 end
        if n > 0 then
            print("[WorkshopBridge] poll heartbeat: tick=" .. wbTick
                .. " activeJobs=" .. n)
        end
    end
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
            if lastJobState[jobId] ~= st.state then
                print("[WorkshopBridge] job " .. tostring(jobId) .. " state: "
                    .. tostring(lastJobState[jobId]) .. " -> " .. tostring(st.state))
                lastJobState[jobId] = st.state
            end
            if cb.onUpdate then pcall(cb.onUpdate, st) end
            if st.state ~= "running" then
                activeJobs[jobId] = nil
                lastJobState[jobId] = nil
                if cb.onDone then pcall(cb.onDone, st) end
            end
        else
            -- unknown job id or Java threw: drop it, report once
            activeJobs[jobId] = nil
            lastJobState[jobId] = nil
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
-- One panel per Mods screen, stored on the screen itself and built EAGERLY
-- when the menu opens (WB_HookInstance), i.e. in the same construction
-- context as the working menu buttons and download dialog. An earlier
-- version built it lazily on tick inside a pcall'd poll callback: any
-- construction failure there was swallowed silently and the module-level
-- singleton stayed poisoned forever, so nothing ever rendered. Building it
-- up-front means a failure surfaces in the log immediately, and there is
-- no cross-screen stale-parent hazard.

local progressParent = nil -- screen owning the currently-visible panel
local progressBase = ""
local progressVisible = false
local flashHideAt = nil
local errorStuck = false

local function WB_CurrentPanel()
    if progressParent and progressParent.wbProgressPanel then
        return progressParent.wbProgressPanel
    end
    return nil
end

-- Build (once per screen) the progress panel. Same construction order as
-- the working download dialog: new -> initialise -> instantiate -> addChild.
function WB_EnsureProgressPanel(parent)
    if not parent then return nil end
    if parent.wbProgressPanel then return parent.wbProgressPanel end
    local w, h = 380, 48
    local x = math.max(0, parent:getWidth() / 2 - w / 2)
    local y = math.max(0, parent:getHeight() / 2 - h / 2)
    local panel = ISPanel:new(x, y, w, h)
    panel:initialise()
    panel:instantiate() -- once; base createChildren is empty
    panel.backgroundColor = { r = 0.03, g = 0.03, b = 0.03, a = 0.93 }
    panel.borderColor = { r = 0.45, g = 0.45, b = 0.45, a = 1.0 }
    -- click dismisses a stuck error (instance-level override; vanilla
    -- ISPanel:onMouseUp is a no-op unless moveWithMouse)
    panel.onMouseUp = function(self, px, py)
        WB_HideProgress()
    end
    local label = ISLabel:new(12, 14, 20, "", 1, 1, 1, 1, UIFont.Small, true)
    label:initialise()
    label:instantiate()
    panel:addChild(label)
    panel.wbLabel = label
    parent:addChild(panel)
    panel:setVisible(false)
    parent.wbProgressPanel = panel
    print("[WorkshopBridge] progress panel created at "
        .. math.floor(x) .. "," .. math.floor(y)
        .. " java=" .. tostring(panel.javaObject ~= nil)
        .. " labelJava=" .. tostring(label.javaObject ~= nil))
    return panel
end

function WB_ShowProgress(parent, message)
    if not parent then return end
    local panel = WB_EnsureProgressPanel(parent)
    if not panel then return end
    progressParent = parent
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
    local panel = WB_CurrentPanel()
    if panel then
        WB_SetLabel(panel.wbLabel, progressBase .. "  (click to dismiss)")
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
    local panel = WB_CurrentPanel()
    if progressVisible and panel then
        local dots = string.rep(".", math.floor(tick / 20) % 4)
        WB_SetLabel(panel.wbLabel, progressBase .. dots)
    end
end

function WB_HideProgress()
    flashHideAt = nil
    errorStuck = false
    progressVisible = false
    local panel = WB_CurrentPanel()
    if panel then panel:setVisible(false) end
end
