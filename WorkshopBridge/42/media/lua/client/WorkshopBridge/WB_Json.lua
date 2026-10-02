-- WB_Json: minimal pure-Lua JSON decoder.
-- Handles objects, arrays, strings (with escapes incl. \uXXXX), numbers,
-- true/false/null. Only what WorkshopBridge needs (decoding job status).

function WB_JsonDecode(s)
    local pos = 1
    local function err(msg)
        error("WB_JsonDecode error at " .. pos .. ": " .. msg, 2)
    end
    local function skipWs()
        while pos <= #s and s:sub(pos, pos):match("%s") do
            pos = pos + 1
        end
    end
    local parseValue -- forward declaration
    local function parseString()
        pos = pos + 1 -- opening quote
        local out = {}
        while pos <= #s do
            local c = s:sub(pos, pos)
            if c == '"' then
                pos = pos + 1
                return table.concat(out)
            end
            if c == "\\" then
                local e = s:sub(pos + 1, pos + 1)
                pos = pos + 2
                if e == "n" then out[#out + 1] = "\n"
                elseif e == "t" then out[#out + 1] = "\t"
                elseif e == "r" then out[#out + 1] = "\r"
                elseif e == "b" then out[#out + 1] = "\b"
                elseif e == "f" then out[#out + 1] = "\f"
                elseif e == "u" then
                    local hex = s:sub(pos, pos + 3)
                    pos = pos + 4
                    local code = tonumber(hex, 16)
                    if not code then err("bad \\u escape") end
                    -- combine surrogate pairs; lone surrogates are an error
                    if code >= 0xD800 and code <= 0xDBFF then
                        local lo = nil
                        if s:sub(pos, pos + 1) == "\\u" then
                            lo = tonumber(s:sub(pos + 2, pos + 5), 16)
                        end
                        if lo and lo >= 0xDC00 and lo <= 0xDFFF then
                            code = 0x10000 + (code - 0xD800) * 0x400 + (lo - 0xDC00)
                            pos = pos + 6
                        else
                            err("lone high surrogate in \\u escape")
                        end
                    elseif code >= 0xDC00 and code <= 0xDFFF then
                        err("lone low surrogate in \\u escape")
                    end
                    -- encode as UTF-8
                    if code < 0x80 then
                        out[#out + 1] = string.char(code)
                    elseif code < 0x800 then
                        out[#out + 1] = string.char(
                            0xC0 + math.floor(code / 64), 0x80 + (code % 64))
                    elseif code < 0x10000 then
                        out[#out + 1] = string.char(
                            0xE0 + math.floor(code / 4096),
                            0x80 + (math.floor(code / 64) % 64),
                            0x80 + (code % 64))
                    else
                        out[#out + 1] = string.char(
                            0xF0 + math.floor(code / 262144),
                            0x80 + (math.floor(code / 4096) % 64),
                            0x80 + (math.floor(code / 64) % 64),
                            0x80 + (code % 64))
                    end
                else
                    out[#out + 1] = e -- \" \\ \/ and anything else literal
                end
            else
                out[#out + 1] = c
                pos = pos + 1
            end
        end
        err("unterminated string")
    end
    local function parseNumber()
        local num = s:match("^-?%d+%.?%d*[eE]?[+-]?%d*", pos)
        if not num or num == "" then err("bad number") end
        pos = pos + #num
        local n = tonumber(num)
        if not n then err("bad number '" .. num .. "'") end
        return n
    end
    local function parseArray()
        pos = pos + 1 -- [
        local arr = {}
        skipWs()
        if s:sub(pos, pos) == "]" then pos = pos + 1 return arr end
        while true do
            local v = parseValue()
            if v ~= nil then arr[#arr + 1] = v end
            skipWs()
            local c = s:sub(pos, pos)
            pos = pos + 1
            if c == "]" then return arr end
            if c ~= "," then err("expected ',' or ']'") end
            skipWs()
        end
    end
    local function parseObject()
        pos = pos + 1 -- {
        local obj = {}
        skipWs()
        if s:sub(pos, pos) == "}" then pos = pos + 1 return obj end
        while true do
            skipWs()
            if s:sub(pos, pos) ~= '"' then err("expected string key") end
            local k = parseString()
            skipWs()
            if s:sub(pos, pos) ~= ":" then err("expected ':'") end
            pos = pos + 1
            obj[k] = parseValue()
            skipWs()
            local c = s:sub(pos, pos)
            pos = pos + 1
            if c == "}" then return obj end
            if c ~= "," then err("expected ',' or '}'") end
        end
    end
    parseValue = function()
        skipWs()
        local c = s:sub(pos, pos)
        if c == "{" then return parseObject() end
        if c == "[" then return parseArray() end
        if c == '"' then return parseString() end
        if c == "t" and s:sub(pos, pos + 3) == "true" then pos = pos + 4 return true end
        if c == "f" and s:sub(pos, pos + 4) == "false" then pos = pos + 5 return false end
        if c == "n" and s:sub(pos, pos + 3) == "null" then pos = pos + 4 return nil end
        if c == "-" or c:match("%d") then return parseNumber() end
        err("unexpected character '" .. c .. "'")
    end
    local v = parseValue()
    skipWs()
    if pos <= #s then err("trailing characters after JSON value") end
    return v
end
