dofile(os.getenv("WB_LUA_DIR") .. "/WB_Json.lua")
local fails = 0
local function check(c, n, e)
    if c then print("PASS " .. n) else print("FAIL " .. n .. (e and " -- "..e or "")); fails = fails + 1 end
end
local f = assert(io.open(os.getenv("WB_STATUSES") or "/tmp/wb-luatest/java_statuses.txt"))
local lines = {}
for l in f:lines() do lines[#lines+1] = l end
f:close()
check(#lines == 3, "three statuses", #lines)
local r = WB_JsonDecode(lines[1])
check(r.state == "running" and r.done == 2 and r.total == 5, "running fields")
check(r.current == 'Downloading "Cool Mod" (id 12345) \\ path', "escapes round-trip", r.current)
local d = WB_JsonDecode(lines[2])
check(d.state == "done" and #d.updates == 2 and d.updates[1] == "ModA"
    and #d.installed == 1 and #d.failed == 0, "done fields + lists")
local x = WB_JsonDecode(lines[3])
check(x.state == "failed" and x.error ==
    "Couldn't reach Steam's servers - check your internet connection.", "failed fields")
print(fails == 0 and "ROUND-TRIP OK" or fails .. " FAILURES")
os.exit(fails == 0 and 0 or 1)
