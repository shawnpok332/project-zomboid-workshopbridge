-- WorkshopBridge debug stub: fakes the ZombieBuddy Java API with canned
-- responses so the whole UI can be verified in-game without building Java.
--
-- This file has NO side effects on load; WB_Main calls WB_InstallDebugStub()
-- only when the real Java API is absent and WB_Config.DEBUG_STUB is true.

local STUB_WORKSHOP_ID = "1234567890"

-- modIds the stub has seen via wbGetWorkshopId (used to fake a check result)
local seenModIds = {}

local stubJobs = {}
local jobSeq = 0

local function newJob(kind, total)
    jobSeq = jobSeq + 1
    local id = "stub-" .. kind .. "-" .. jobSeq
    stubJobs[id] = { kind = kind, step = 0, total = total, calls = 0 }
    return id
end

function WB_InstallDebugStub()
    if type(wbIsAvailable) == "function" then return false end -- real API present

    function wbIsAvailable()
        return true
    end

    function wbGetSteamCmdPath()
        return "stub/steamcmd"
    end

    function wbGetWorkshopId(modId)
        -- Every mod looks "known" except our own, so both UI states are visible.
        if not modId or modId == WB_Config.MOD_ID then return nil end
        seenModIds[modId] = true
        return STUB_WORKSHOP_ID
    end

    function wbCheckForUpdates()
        return newJob("check", 6)
    end

    function wbUpdateAll()
        return newJob("updateAll", 5)
    end

    function wbUpdateMod(workshopId)
        return newJob("update", 8)
    end

    function wbGetJobStatus(jobId)
        local j = stubJobs[jobId]
        if not j then return nil end
        j.calls = j.calls + 1
        -- advance slowly so the progress panel / throbber is actually visible
        if j.calls % 12 ~= 0 then
            return {
                state = "running", done = j.step, total = j.total,
                message = (j.kind == "check" and "Checking... " or "Working... ")
                    .. j.step .. "/" .. j.total,
            }
        end
        j.step = j.step + 1
        if j.step >= j.total then
            stubJobs[jobId] = nil
            if j.kind == "check" then
                -- report the first-seen mod as having an update, so the
                -- "Update available" badge path can be verified end to end
                local first = nil
                for id in pairs(seenModIds) do first = id; break end
                return {
                    state = "done", done = j.total, total = j.total,
                    message = "Check complete",
                    updates = first and { first } or {},
                }
            end
            return { state = "done", done = j.total, total = j.total, message = "Done" }
        end
        return {
            state = "running", done = j.step, total = j.total,
            message = "Working... " .. j.step .. "/" .. j.total,
        }
    end

    print("[WorkshopBridge] DEBUG STUB installed (fake Java API)")
    return true
end
