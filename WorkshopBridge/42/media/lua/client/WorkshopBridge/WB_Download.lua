-- WorkshopBridge "download a new mod" UI.
--
-- The Java side already supports this: wbUpdateMod(workshopId) downloads,
-- installs and records ANY valid workshop id, tracked or not. This file is
-- just the Lua half: a Download button in the Mods menu, a small dialog
-- taking a workshop ID or URL, and the usual job tracking via WB_Jobs.
require "WorkshopBridge/WB_Config"
require "WorkshopBridge/WB_Jobs"

-- Parse a workshop id out of free-form input: a bare id ("2685600088"),
-- "id=2685600088", or a full URL
-- ("https://steamcommunity.com/sharedfiles/filedetails/?id=2685600088&...").
-- Returns the id string, or nil when nothing usable is found.
function WB_ParseWorkshopId(input)
    if type(input) ~= "string" then return nil end
    local s = input:match("^%s*(.-)%s*$")
    if not s or s == "" then return nil end
    local fromUrl = s:match("[?&]id=(%d+)") or s:match("^id=(%d+)$")
    if fromUrl then return fromUrl end
    if s:match("^%d+$") then return s end
    return nil
end

WB_DownloadDialog = ISPanel:derive("WB_DownloadDialog")

-- NOTE: deliberately no initialise()/createChildren() overrides. PZ's
-- ISUIElement:instantiate() calls createChildren() itself, so building
-- controls there (plus an explicit instantiate()) constructs the dialog
-- several times with discarded Java peers. Controls are built once, by an
-- explicit call after instantiate().
function WB_DownloadDialog:buildControls()
    local pad = 12
    local w = self:getWidth()
    self.titleLabel = ISLabel:new(pad, pad, 20, WB_Text.DownloadModTitle,
        1, 1, 1, 1, UIFont.Small, true)
    self.hintLabel = ISLabel:new(pad, 34, 20, WB_Text.DownloadHint,
        0.7, 0.7, 0.7, 1, UIFont.Small, true)
    self.entry = ISTextEntryBox:new("", pad, 56, w - pad * 2, 26)
    self.errorLabel = ISLabel:new(pad, 88, 20, "",
        1, 0.35, 0.35, 1, UIFont.Small, true)
    self.downloadBtn = ISButton:new(pad, 116, 140, 28, WB_Text.Download, self,
        function() self:onDownloadClicked() end)
    self.cancelBtn = ISButton:new(pad + 150, 116, 140, 28, WB_Text.Cancel, self,
        function() self:close() end)
    for _, c in ipairs({ self.titleLabel, self.hintLabel, self.entry,
                         self.errorLabel, self.downloadBtn, self.cancelBtn }) do
        c:initialise()
        self:addChild(c)
    end
    self.downloadBtn:setFont(UIFont.Small)
    self.cancelBtn:setFont(UIFont.Small)
end

function WB_DownloadDialog:onDownloadClicked()
    local ms = self.ms
    local wsid = WB_ParseWorkshopId(self.entry:getText())
    if not wsid then
        WB_SetLabel(self.errorLabel, WB_Text.InvalidWorkshopId)
        return
    end
    self:close()
    if type(wbUpdateMod) ~= "function" then
        WB_FlashMessage(ms, WB_Text.DownloadFailed)
        return
    end
    local ok, jobId = pcall(wbUpdateMod, wsid)
    if not ok or not jobId then
        WB_FlashMessage(ms, WB_Text.DownloadFailed)
        return
    end
    WB_TrackJob(jobId, {
        onUpdate = function(st)
            WB_ShowProgress(ms, st.message or WB_Text.Downloading)
        end,
        onDone = function(st)
            WB_HideProgress()
            if st and st.state == "failed" then
                WB_ShowError(ms,
                    WB_Text.DownloadFailed .. ": " .. WB_ShortError(st.error, 64))
            else
                WB_FlashMessage(ms, WB_Text.Downloaded)
                -- rescan so the new mod shows up; then make sure our
                -- menu hooks survived (re-applied defensively)
                if ms and ms.reloadMods then pcall(function() ms:reloadMods() end) end
                WB_HookInstance(ms)
            end
        end,
    })
end

function WB_DownloadDialog:close()
    local ms = self.ms
    self:setVisible(false)
    if ms then
        pcall(function() ms:removeChild(self) end)
        if ms.wbDownloadDialog == self then ms.wbDownloadDialog = nil end
    end
end

-- Opens the dialog, centered on the ModSelector screen. Reuses the open
-- one instead of stacking duplicates.
function WB_ShowDownloadDialog(ms)
    if not ms then return end
    if ms.wbDownloadDialog then
        ms.wbDownloadDialog:setVisible(true)
        return
    end
    local w, h = 420, 170
    local dlg = WB_DownloadDialog:new(
        math.max(0, ms:getWidth() / 2 - w / 2),
        math.max(0, ms:getHeight() / 2 - h / 2), w, h)
    dlg.ms = ms
    dlg:initialise()
    dlg:instantiate() -- once; createChildren is the empty base version
    dlg:buildControls()
    dlg.backgroundColor = { r = 0.05, g = 0.05, b = 0.05, a = 0.97 }
    dlg.borderColor = { r = 0.45, g = 0.45, b = 0.45, a = 1.0 }
    ms:addChild(dlg)
    ms.wbDownloadDialog = dlg
end
