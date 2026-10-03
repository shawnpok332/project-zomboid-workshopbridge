-- WorkshopBridge background-job polling + progress UI.
--
-- Java downloads/checks run on background threads; Lua polls job status so
-- the game thread never blocks. The poll runs on Events.OnTick with a
-- fallback pump from the Mods screen's per-frame update() (the tick doesn't
-- reliably fire while a main-menu screen is open). Polling is idempotent,
-- so both pumps running at once is safe; UI timers (flash, throbber) are
-- advanced by the fallback pump only, so they can't run double speed.
-- Status table shape is defined in docs/ARCHITECTURE.md ("Job status shape").
require "WorkshopBridge/WB_Config"
require "WorkshopBridge/WB_Json"

-- workshop id from OUR map via the Java API; nil = "Unknown workshop ID"
-- (used by both the menu and the update-available state below)
function WB_WorkshopIdFor(modId)
    if type(wbGetWorkshopId) ~= "function" or not modId then return nil end
    local ok, wsid = pcall(wbGetWorkshopId, modId)
    if ok and wsid and wsid ~= "" then return wsid end
    return nil
end

local activeJobs = {}       -- jobId -> { onUpdate=fn, onDone=fn }
local lastJobState = {}     -- jobId -> last seen state (for transition logging)
-- workshopId -> true, set by check jobs. Keyed by workshop id, not mod id:
-- the workshop item is the update unit (one item can hold several mods),
-- so updating via any one of its mods clears the flag for all of them.
local updateAvailable = {}
local wbTick = 0            -- advanced ONLY by the Mods-screen fallback pump
local tickHooked = false
local currentJob = nil      -- jobId whose poll callback is currently running
local panelOwner = nil      -- jobId owning the shared progress panel, if any

-- ---------- job tracking ----------

function WB_TrackJob(jobId, callbacks)
    if not jobId then return end
    activeJobs[jobId] = callbacks or {}
    WB_EnsurePolling()
end

function WB_MarkUpdateAvailable(workshopId)
    updateAvailable[workshopId] = true
end

function WB_UnmarkUpdateAvailable(workshopId)
    updateAvailable[workshopId] = nil
end

function WB_ClearUpdateAvailable()
    for k in pairs(updateAvailable) do updateAvailable[k] = nil end
end

-- modId here is the caller's handle; the lookup resolves it to the
-- workshop id via the map, so every mod of a multi-mod item shares one flag
function WB_IsUpdateAvailable(modId)
    if not modId then return false end
    local wsid = WB_WorkshopIdFor(modId)
    return wsid ~= nil and updateAvailable[wsid] == true
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

-- Fallback pump entry point for the Mods screen's per-frame update()
-- (see WB_ModsMenu). This is the ONLY place wbTick advances: both pumps
-- call WB_PollJobs, so advancing the tick there would run the flash and
-- throbber timers at double speed whenever both pumps run.
function WB_FallbackPump()
    wbTick = wbTick + 1
    WB_PollJobs()
end

function WB_PollJobs()
    -- NOTE: wbTick is advanced ONLY by the Mods-screen fallback pump (see
    -- WB_ModsMenu), not here. Both pumps call this function, and advancing
    -- the tick in both would run flash/throbber timers at double speed.
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
        -- currentJob lets WB_ShowProgress attribute panel paints to the job
        -- whose callback is running (panel ownership, below)
        currentJob = jobId
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
                if panelOwner == jobId then panelOwner = nil end
                if cb.onDone then pcall(cb.onDone, st) end
            end
        else
            -- unknown job id or Java threw: drop it, report once
            activeJobs[jobId] = nil
            lastJobState[jobId] = nil
            if panelOwner == jobId then panelOwner = nil end
            print("[WorkshopBridge] job " .. tostring(jobId) .. " failed: " .. tostring(st))
            if cb.onDone then pcall(cb.onDone, { state = "failed", error = tostring(st) }) end
        end
        currentJob = nil
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
local errorActive = nil     -- activeJobs snapshot when a sticky error was set

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
        WB_HideProgress(true)
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

-- Shared painter behind WB_ShowProgress / WB_ShowError.
local function WB_PaintPanel(parent, message, isError)
    if not parent then return end
    if errorStuck and not isError and currentJob
            and errorActive and errorActive[currentJob] then
        -- a stuck error holds the panel against jobs that were already
        -- running when it was set; anything newer clears it below
        return
    end
    if not isError and panelOwner and panelOwner ~= currentJob
            and activeJobs[panelOwner] then
        -- Panel ownership: concurrent jobs share the one panel. The first
        -- job to paint owns it until it completes; a concurrent job's
        -- paints are ignored so the label can't flicker between two
        -- messages. Ownership is released when the owner completes (see
        -- WB_PollJobs) and passes to the next painter. Errors always
        -- paint: they steal the panel.
        return
    end
    local panel = WB_EnsureProgressPanel(parent)
    if not panel then return end
    progressParent = parent
    panelOwner = currentJob
    progressBase = message or ""
    flashHideAt = nil
    if isError then
        errorStuck = true
        errorActive = {}
        for id in pairs(activeJobs) do errorActive[id] = true end
    else
        errorStuck = false
        errorActive = nil
    end
    WB_SetLabel(panel.wbLabel, progressBase)
    panel:setVisible(true)
    progressVisible = true
end

function WB_ShowProgress(parent, message)
    WB_PaintPanel(parent, message, false)
end

-- Show an error in the progress panel and keep it there until the user
-- clicks it away. Flash messages vanish after ~2.5s, too fast to read a
-- failure; errors need to wait for the user, not the other way round.
function WB_ShowError(parent, message)
    if not parent then return end
    WB_PaintPanel(parent, message, true)
    local panel = WB_CurrentPanel()
    if panel then
        WB_SetLabel(panel.wbLabel, progressBase .. "  (click to dismiss)")
    end
end

-- Show a message briefly, then auto-hide (for check/update result summaries).
function WB_FlashMessage(parent, message)
    WB_ShowProgress(parent, message)
    flashHideAt = wbTick + 150 -- ~2.5s at 60 ticks/s
end

function WB_TickProgressPanel(tick)
    if flashHideAt and tick >= flashHideAt then
        -- flash done: force-hide even if a job is still tracked, its next
        -- onUpdate repaints immediately
        WB_HideProgress(true)
        return
    end
    if errorStuck then return end -- stuck error: no throbber dots, no auto-hide
    local panel = WB_CurrentPanel()
    if progressVisible and panel then
        local dots = string.rep(".", math.floor(tick / 20) % 4)
        WB_SetLabel(panel.wbLabel, progressBase .. dots)
    end
end

function WB_HideProgress(force)
    -- A completing job must not hide a still-running job's status, nor a
    -- stuck error awaiting dismissal: hide only when nothing needs the
    -- panel, unless forced (user click-dismiss or flash expiry, where a
    -- running job repaints on its next update).
    if not force then
        if errorStuck then return end
        for _ in pairs(activeJobs) do return end
    end
    flashHideAt = nil
    errorStuck = false
    errorActive = nil
    progressVisible = false
    panelOwner = nil
    local panel = WB_CurrentPanel()
    if panel then panel:setVisible(false) end
end
