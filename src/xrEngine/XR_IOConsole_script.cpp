#include "stdafx.h"

#include "XR_IOConsole.h"
#include "xr_ioc_cmd.h"

#include "xrScriptEngine/script_space.hpp"

void CConsole::script_register(lua_State* luaState)
{
    using namespace luabind;

    module(luaState)
    [
        class_<CConsole>("CConsole")
            .def("execute", &CConsole::Execute)
            .def("execute_script", &CConsole::ExecuteScript)
            .def("show", &CConsole::Show)
            .def("hide", &CConsole::Hide)

            .def("get_string", &CConsole::GetString)
            .def("get_integer", +[](const CConsole* self, pcstr cmd)
            {
                int min = 0, max = 0;
                const int val = self->GetInteger(cmd, min, max);
                return val;
            })
            .def("get_bool", +[](const CConsole* self, pcstr cmd)
            {
                return self->GetBool(cmd);
            })
            .def("get_float", +[](const CConsole* self, pcstr cmd)
            {
                float min = 0.0f, max = 0.0f;
                const float val = self->GetFloat(cmd, min, max);
                return val;
            })
            .def("get_token", &CConsole::GetToken)
            .def("execute_deferred", +[](CConsole*, pcstr string_to_execute)
            {
                Engine.Event.Defer("KERNEL:console", size_t(xr_strdup(string_to_execute)));
            }),

        def("get_console", +[]() -> CConsole*
        {
            // Many game scripts call this from top-level code while their chunk
            // is still loading (xrs_dyn_music, ui_main_menu, ui_mp_main,
            // ui_load_dialog, ui_save_dialog, ...). Returning null aborted the
            // chunk mid-way, so every function defined below that point never
            // existed -- and the visible crash was xr_s.register_callback
            // receiving nil and dying with "table index is nil" several files
            // away from the real cause.
            //
            // The Lua proxy installed at the end of this function cannot fix
            // that: it is assigned into _G by luaL_dostring, while scripts
            // resolve the name through luabind's module environment, and the
            // result of luaL_dostring is never checked, so the override silently
            // never took effect. Guarantee a valid object at the binding itself.
            static CConsole* fallback = nullptr;
            if (Console != nullptr)
            {
                static bool reported = false;
                if (!reported)
                {
                    reported = true;
                    Msg("[console-bind] get_console -> real Console %p", (void*)Console);
                }
                return Console;
            }
            if (fallback == nullptr)
            {
                fallback = new CConsole();
                Msg("[console-bind] Console is NULL, created fallback %p. Scripts that call "
                    "get_console() before the real console exists get an empty command table, "
                    "so every get_* silently returns a default.",
                    (void*)fallback);
            }
            return fallback;
        }),

        def("execute_console", +[](pcstr cmd)
        {
            Msg(">>> [execute_console] cmd='%s'", cmd ? cmd : "<null>");
            FlushLog();
            if (Console && cmd)
                Console->Execute(cmd);
        }),

        def("execute_console_deferred", +[](pcstr cmd)
        {
            Msg(">>> [execute_console_deferred] cmd='%s'", cmd ? cmd : "<null>");
            FlushLog();
            // Deferred via KERNEL:console event: runs on the next Engine.OnFrame,
            // outside of any Lua dialog callback stack. Required for level-switching
            // commands ("start ...", "disconnect", "main_menu off", "load ...")
            // issued from main-menu UI buttons (synchronous Execute from inside
            // a dialog callback hangs the game on Android).
            if (cmd)
                Engine.Event.Defer("KERNEL:console", size_t(xr_strdup(cmd)));
        }),

        def("renderer_allow_override", +[]()
        {
            return renderer_allow_override;
        })
    ];

    // Probe from C++ on purpose: at this point in startup the Lua environment has
    // almost no globals yet (setmetatable and log are both nil), so a Lua-side
    // probe silently does nothing. This reports exactly what the binding hands
    // to scripts, instead of inferring it from the error text.
    struct
    {
        const char* name;
    } probe_names[] = { { "setmetatable" }, { "log" }, { "get_console" }, { "device" }, { "alife" } };

    for (const auto& pn : probe_names)
    {
        // LuaJIT is 5.1: lua_getglobal is a macro over lua_getfield and returns
        // void, so the value has to be inspected on the stack.
        lua_getfield(luaState, LUA_GLOBALSINDEX, pn.name);
        Msg("[console-bind] global '%s' is %s", pn.name,
            lua_typename(luaState, lua_type(luaState, -1)));
        lua_pop(luaState, 1);
    }

    lua_getfield(luaState, LUA_GLOBALSINDEX, "get_console");
    if (lua_isfunction(luaState, -1))
    {
        if (lua_pcall(luaState, 0, 1, 0) == LUA_OK)
        {
            const int type = lua_type(luaState, -1);
            Msg("[console-bind] C++ probe: get_console() -> %s", lua_typename(luaState, type));
            if (lua_isuserdata(luaState, -1) && lua_getmetatable(luaState, -1))
            {
                Msg("[console-bind]   metatable is %s", lua_typename(luaState, lua_type(luaState, -1)));
                lua_pushstring(luaState, "__index");
                lua_rawget(luaState, -2);
                Msg("[console-bind]   __index is %s", lua_typename(luaState, lua_type(luaState, -1)));
                lua_pop(luaState, 1);
                lua_pop(luaState, 1);
            }
            else if (lua_istable(luaState, -1))
            {
                lua_pushstring(luaState, "__index");
                lua_rawget(luaState, -2);
                Msg("[console-bind]   table, __index is %s", lua_typename(luaState, lua_type(luaState, -1)));
                lua_pop(luaState, 1);
            }
        }
        else
        {
            Msg("[console-bind] C++ probe: get_console() raised: %s",
                lua_tostring(luaState, -1));
        }
        lua_pop(luaState, 1);
    }

    // Ensure get_console in Lua always returns a working console proxy calling execute_console directly
    const int proxy_rc = luaL_dostring(luaState,
        "local real_get_console = get_console\n"
        "local console_proxy = {\n"
        "    execute = function(self, cmd)\n"
        "        if cmd then\n"
        "            if execute_console then\n"
        "                execute_console(cmd)\n"
        "            else\n"
        "                local c = real_get_console and real_get_console()\n"
        "                if c and c.execute then c:execute(cmd) end\n"
        "            end\n"
        "        end\n"
        "    end,\n"
        "    execute_deferred = function(self, cmd)\n"
        "        if cmd then\n"
        "            if execute_console_deferred then\n"
        "                execute_console_deferred(cmd)\n"
        "            elseif execute_console then\n"
        "                execute_console(cmd)\n"
        "            end\n"
        "        end\n"
        "    end,\n"
        "    execute_script = function(self, cmd)\n"
        "        local c = real_get_console and real_get_console()\n"
        "        if c and c.execute_script then c:execute_script(cmd) end\n"
        "    end,\n"
        "    show = function(self)\n"
        "        local c = real_get_console and real_get_console()\n"
        "        if c and c.show then c:show() end\n"
        "    end,\n"
        "    hide = function(self)\n"
        "        local c = real_get_console and real_get_console()\n"
        "        if c and c.hide then c:hide() end\n"
        "    end,\n"
        "    get_string = function(self, cmd)\n"
        "        local c = real_get_console and real_get_console()\n"
        "        return (c and c.get_string) and c:get_string(cmd) or ''\n"
        "    end,\n"
        "    get_integer = function(self, cmd)\n"
        "        local c = real_get_console and real_get_console()\n"
        "        return (c and c.get_integer) and c:get_integer(cmd) or 0\n"
        "    end,\n"
        "    get_bool = function(self, cmd)\n"
        "        local c = real_get_console and real_get_console()\n"
        "        return (c and c.get_bool) and c:get_bool(cmd) or false\n"
        "    end,\n"
        "    get_float = function(self, cmd)\n"
        "        local c = real_get_console and real_get_console()\n"
        "        return (c and c.get_float) and c:get_float(cmd) or 0.0\n"
        "    end,\n"
        "    get_token = function(self, cmd)\n"
        "        local c = real_get_console and real_get_console()\n"
        "        return (c and c.get_token) and c:get_token(cmd) or ''\n"
        "    end\n"
        "}\n"
        "setmetatable(console_proxy, {\n"
        "    __index = function(t, k)\n"
        "        local c = real_get_console and real_get_console()\n"
        "        if c then return c[k] end\n"
        "        return nil\n"
        "    end\n"
        "})\n"
        "get_console = function() return console_proxy end\n"
    );
    if (proxy_rc != 0)
    {
        Msg("[console-bind] console proxy failed to install: %s",
            lua_tostring(luaState, -1));
        lua_pop(luaState, 1);
    }
}
