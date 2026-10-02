-- WorkshopBridge background-job polling (skeleton - Phase 1).
--
-- Java downloads run on background threads; Lua polls job status on tick so the
-- game thread never blocks. Status table shape is defined in
-- docs/ARCHITECTURE.md ("Job status shape").

local activeJobs = {} -- jobId -> { onUpdate = fn, onDone = fn }

function WB_TrackJob(jobId, callbacks)
    activeJobs[jobId] = callbacks or {}
end

local function WB_PollJobs()
    -- TODO(Phase 2/3):
    --   for jobId, cb in pairs(activeJobs) do
    --       local st = wbGetJobStatus(jobId) -- via ZombieBuddy Java API
    --       if st then
    --           if cb.onUpdate then cb.onUpdate(st) end
    --           if st.state ~= "running" then
    --               if cb.onDone then cb.onDone(st) end
    --               activeJobs[jobId] = nil
    --           end
    --       end
    --   end
end

-- TODO(review, Phase 2): enable tick polling once the Java side exists.
-- Events.OnTick.Add(WB_PollJobs)
