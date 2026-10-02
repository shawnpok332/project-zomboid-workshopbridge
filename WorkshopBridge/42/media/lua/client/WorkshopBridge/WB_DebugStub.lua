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

-- The real wbGetJobStatus returns a JSON string; the stub mirrors that so the
-- UI path under test is identical.
local function jesc(s)
    return '"' .. tostring(s):gsub('[%z\1-\31\\"]', function(c)
        if c == '"' then return '\\"'
        elseif c == '\\' then return '\\\\'
        elseif c == '\n' then return '\\n'
        elseif c == '\r' then return '\\r'
        elseif c == '\t' then return '\\t'
        else return string.format('\\u%04x', c:byte()) end
    end) .. '"'
end

local function statusJson(state, done, total, message, updates)
    local up = {}
    for _, id in ipairs(updates or {}) do up[#up + 1] = jesc(id) end
    return '{"state":' .. jesc(state)
        .. ',"done":' .. done
        .. ',"total":' .. total
        .. ',"message":' .. jesc(message)
        .. ',"updates":[' .. table.concat(up, ",") .. ']}'
end

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
        local function running()
            return statusJson("running", j.step, j.total,
                (j.kind == "check" and "Checking... " or "Working... ")
                    .. j.step .. "/" .. j.total)
        end
        -- advance slowly so the progress panel / throbber is actually visible
        if j.calls % 12 ~= 0 then return running() end
        j.step = j.step + 1
        if j.step < j.total then return running() end
        stubJobs[jobId] = nil
        if j.kind == "check" then
            -- report every seen mod as having an update (sorted, so the
            -- stub is deterministic run to run - pairs() order is not)
            local all = {}
            for id in pairs(seenModIds) do all[#all + 1] = id end
            table.sort(all)
            return statusJson("done", j.total, j.total, "Check complete", all)
        end
        return statusJson("done", j.total, j.total, "Done")
    end

    print("[WorkshopBridge] DEBUG STUB installed (fake Java API)")
    return true
end
