-- WorkshopBridge Mods-menu UI (skeleton - Phase 1).
--
-- Planned (Phase 2):
--   * an "Update all" button on the Mods screen
--   * per mod row: an "Update" button when the Java side knows the workshop ID
--     for that mod id, otherwise a greyed-out "Unknown workshop ID" label
--
-- TODO(research): exact B42 Mods-screen class and row class names, and the
-- recommended hook point (event vs wrapping a screen method). Research in flight.

local function WB_HookModsMenu()
    -- TODO(Phase 2): hook Mods screen creation, then:
    --   WB_AddUpdateAllButton(screen)
    --   for each row: WB_DecorateModRow(row, modId)
end

function WB_AddUpdateAllButton(screen)
    -- TODO(Phase 2)
end

function WB_DecorateModRow(row, modId)
    -- TODO(Phase 2):
    --   local workshopId = wbGetWorkshopId(modId) -- via ZombieBuddy Java API
    --   if workshopId then
    --       add "Update" button -> jobId = wbUpdateMod(workshopId); WB_TrackJob(jobId, row)
    --   else
    --       add grey label "Unknown workshop ID"
    --   end
end
