-- WorkshopBridge background-job polling + progress UI.
--
-- Java downloads/checks run on background threads; Lua polls job status on
-- tick so the game thread never blocks. Status table shape is defined in
-- docs/ARCHITECTURE.md ("Job status shape").
require "WorkshopBridge/WB_Config"

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
        local ok, st = pcall(wbGetJobStatus, jobId)
        if ok and type(st) == "table" then
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

-- ---------- progress panel (throbber) ----------

WB_ProgressPanel = ISPanel:derive("WB_ProgressPanel")

function WB_ProgressPanel:initialise()
    ISPanel.initialise(self)
    self:createChildren()
end

function WB_ProgressPanel:createChildren()
    self.label = ISLabel:new(12, 14, 20, "", 1, 1, 1, 1, UIFont.Small, true)
    self.label:initialise()
    self.label:instantiate()
    self:addChild(self.label)
end

function WB_ProgressPanel:setMessage(text)
    WB_SetLabel(self.label, text)
end

local progressPanel = nil
local progressBase = ""
local progressVisible = false
local flashHideAt = nil

function WB_ShowProgress(parent, message)
    if not parent then return end
    if not progressPanel then
        local w, h = 380, 48
        progressPanel = WB_ProgressPanel:new(
            math.max(0, parent:getWidth() / 2 - w / 2),
            math.max(0, parent:getHeight() / 2 - h / 2),
            w, h)
        progressPanel:initialise()
        progressPanel:instantiate()
        progressPanel.backgroundColor = { r = 0.03, g = 0.03, b = 0.03, a = 0.93 }
        progressPanel.borderColor = { r = 0.45, g = 0.45, b = 0.45, a = 1.0 }
        parent:addChild(progressPanel)
    end
    progressBase = message or ""
    flashHideAt = nil
    progressPanel:setMessage(progressBase)
    progressPanel:setVisible(true)
    progressVisible = true
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
    if progressVisible and progressPanel then
        local dots = string.rep(".", math.floor(tick / 20) % 4)
        progressPanel:setMessage(progressBase .. dots)
    end
end

function WB_HideProgress()
    flashHideAt = nil
    progressVisible = false
    if progressPanel then progressPanel:setVisible(false) end
end
