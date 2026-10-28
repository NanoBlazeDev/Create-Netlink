package dev.exavolt.netlink.script;

/** Lua run before every script: Roblox-flavoured helpers and the object wrappers around the Java API. */
final class Prelude {
	private Prelude() {
	}

	static final String LUA = """
		local _connect, _disconnect, _wait = __connect, __disconnect, __wait
		__connect, __disconnect, __wait = nil, nil, nil

		-- Roblox-style signal objects: event:Connect(fn), event:Once(fn), event:Wait()
		local Connection = {}
		Connection.__index = Connection
		Connection.__tostring = function() return "Connection" end
		function Connection:Disconnect()
			if self.Connected then
				self.Connected = false
				_disconnect(self._id)
			end
		end

		local Event = {}
		Event.__index = Event
		Event.__tostring = function(e) return "Event(" .. e._key .. ")" end
		function Event:Connect(fn)
			assert(type(fn) == "function", "Connect expects a function")
			return setmetatable({ Connected = true, _id = _connect(self._key, fn, false) }, Connection)
		end
		function Event:Once(fn)
			assert(type(fn) == "function", "Once expects a function")
			return setmetatable({ Connected = true, _id = _connect(self._key, fn, true) }, Connection)
		end
		function Event:Wait()
			return _wait(self._key)
		end
		local function event(key)
			return setmetatable({ _key = key }, Event)
		end

		-- signal.changed("Channel"):Connect(function(value, old) ... end)
		function signal.changed(channel)
			return event("signal:" .. tostring(channel))
		end
		signal.Changed = signal.changed

		-- world events (operators only)
		if world then
			world.PlayerJoined = event("world:PlayerJoined")
			world.PlayerLeft = event("world:PlayerLeft")
			world.Chatted = event("world:Chatted")
		end

		-- elevators
		local elev_get, elev_set, elev_floors, elev_goto, elev_exists, elev_list =
			__elev_get, __elev_set, __elev_floors, __elev_goto, __elev_exists, __elev_list
		__elev_get, __elev_set, __elev_floors, __elev_goto, __elev_exists, __elev_list = nil, nil, nil, nil, nil, nil

		local Elevator = {}
		local methods = {}
		function methods:GoTo(floor)
			elev_goto(rawget(self, "Name"), floor)
		end
		function methods:GetFloors()
			return elev_floors(rawget(self, "Name"))
		end
		function methods:IsMoving()
			return elev_get(rawget(self, "Name"), "Moving")
		end
		Elevator.__index = function(self, key)
			local m = methods[key]
			if m then return m end
			local name = rawget(self, "Name")
			if key == "Departed" or key == "Arrived" or key == "Requested" then
				local e = event("elevator:" .. name .. ":" .. key)
				rawset(self, key, e)
				return e
			end
			return elev_get(name, key)
		end
		Elevator.__newindex = function(self, key, value)
			elev_set(rawget(self, "Name"), key, value)
		end
		Elevator.__tostring = function(self) return "Elevator(" .. rawget(self, "Name") .. ")" end

		elevator = {}
		function elevator.get(name)
			if not elev_exists(name) then return nil end
			return setmetatable({ Name = name }, Elevator)
		end
		function elevator.wait(name, timeout)
			local waited = 0
			while not elev_exists(name) do
				if timeout and waited >= timeout then return nil end
				waited = waited + task.wait(0.5)
			end
			return elevator.get(name)
		end
		elevator.list = elev_list

		-- error messages without trailing whitespace
		local _pcall, _xpcall = pcall, xpcall
		local function clean(ok, ...)
			if not ok then
				local e = ...
				if type(e) == "string" then e = e:gsub("%s+$", "") end
				return false, e
			end
			return ok, ...
		end
		function pcall(f, ...) return clean(_pcall(f, ...)) end

		-- Luau / Roblox standard library extras
		function math.clamp(x, lo, hi)
			if x < lo then return lo elseif x > hi then return hi end
			return x
		end
		function math.round(x) return math.floor(x + 0.5) end
		function math.sign(x) return x > 0 and 1 or (x < 0 and -1 or 0) end
		function string.split(s, sep)
			sep = sep or ","
			local out, from = {}, 1
			if sep == "" then
				for i = 1, #s do out[i] = s:sub(i, i) end
				return out
			end
			while true do
				local a, b = string.find(s, sep, from, true)
				if not a then
					out[#out + 1] = s:sub(from)
					return out
				end
				out[#out + 1] = s:sub(from, a - 1)
				from = b + 1
			end
		end
		function table.find(t, v, init)
			for i = init or 1, #t do
				if t[i] == v then return i end
			end
			return nil
		end
		function table.clear(t) for k in pairs(t) do t[k] = nil end end
		function table.create(n, v) local t = {} for i = 1, n do t[i] = v end return t end
		function table.freeze(t) return t end
		function table.isfrozen() return false end
		table.unpack = table.unpack or unpack
		unpack = unpack or table.unpack
		function typeof(v)
			local mt = getmetatable(v)
			if mt == Event then return "RBXScriptSignal" end
			if mt == Connection then return "RBXScriptConnection" end
			if mt == Elevator then return "Elevator" end
			return type(v)
		end
		""";
}
