package pcl.lc.irc.hooks;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import li.cil.repack.org.luaj.vm2.*;
import li.cil.repack.org.luaj.vm2.compiler.LuaC;
import li.cil.repack.org.luaj.vm2.lib.*;
import li.cil.repack.org.luaj.vm2.lib.jse.*;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.types.GenericMessageEvent;

import pcl.lc.irc.AbstractListener;
import pcl.lc.irc.Config;
import pcl.lc.irc.IRCBot;
import pcl.lc.utils.Helper;

/** Simple program that illustrates basic sand-boxing of client scripts
 * in a server environment.
 * 
 * <p>Although this sandboxing is done primarily in Java here, these 
 * same techniques should all be possible directly from lua using metatables,
 * and examples are shown in examples/lua/samplesandboxed.lua.
 * 
 * <p> The main goals of this sandbox are:
 * <ul>
 * <li>Lightweight sandbox without using custom class loaders</li>
 * <li>use globals per-script and leave out dangerous libraries</li>
 * <li>use hook functions with Errors to limit lua scripts</li>
 * <li>use read-only tables to protect shared metatables</li>
 * 
 * @see Globals
 * @see LuaValue
 */
public class LuaJSandbox extends AbstractListener {
	// These globals are used by the server to compile scripts.
	static Globals server_globals;
	static Globals user_globals;
	public String dest;

	public String chan;
	public String target = null;
	private String output = "";

	void setOutput(String in) {
		this.output = in;
	}

	String getOutput() {
		return this.output;
	}

	// Run a script in a lua thread and limit it to a certain number
	// of instructions by setting a hook function.
	// Give each script its own copy of globals, but leave out libraries
	// that contain functions that can be abused.
	static String runScriptInSandbox(String script) {
		// This library is dangerous as it gives unfettered access to the
		// entire Java VM, so it's not suitable within this lightweight sandbox. 
		// user_globals.load(new LuajavaLib());

		// Starting coroutines in scripts will result in threads that are 
		// not under the server control, so this libary should probably remain out.
		// user_globals.load(new CoroutineLib());

		// These are probably unwise and unnecessary for scripts on servers,
		// although some date and time functions may be useful.
		// user_globals.load(new JseIoLib());
		// user_globals.load(new JseOsLib());

		// Loading and compiling scripts from within scripts may also be 
		// prohibited, though in theory it should be fairly safe.
		// LoadState.install(user_globals);
		// LuaC.install(user_globals);

		// The debug library must be loaded for hook functions to work, which  
		// allow us to limit scripts to run a certain number of instructions at a time.
		// However we don't wish to expose the library in the user globals,
		// so it is immediately removed from the user globals once created.
		user_globals.load(new DebugLib());
		LuaValue sethook = user_globals.get("debug").get("sethook");
		user_globals.set("debug", LuaValue.NIL);

		// Set up the script to run in its own lua thread, which allows us 
		// to set a hook function that limits the script to a specific number of cycles.
		// Note that the environment is set to the user globals, even though the 
		// compiling is done with the server globals.
		LuaValue chunk = server_globals.load(script, "main", user_globals);
		LuaThread thread = new LuaThread(user_globals, chunk);

		// Set the hook function to immediately throw an Error, which will not be 
		// handled by any Lua code other than the coroutine.
		LuaValue hookfunc = new ZeroArgFunction() {
			public LuaValue call() {
				// A simple lua error may be caught by the script, but a 
				// Java Error will pass through to top and stop the script.
				throw new Error("Script overran resource limits.");
			}
		};
		final int instruction_count = 200;
		sethook.invoke(LuaValue.varargsOf(new LuaValue[] { thread, hookfunc,
				LuaValue.EMPTYSTRING, LuaValue.valueOf(instruction_count) }));

		// When we resume the thread, it will run up to 'instruction_count' instructions
		// then call the hook function which will error out and stop the script.
		Varargs result = thread.resume(LuaValue.NIL);
		try {
			return result.checknotnil(2).tojstring();
		} catch (Exception e) {
			return e.getMessage();
		}
	}

	// Simple read-only table whose contents are initialized from another table.
	static class ReadOnlyLuaTable extends LuaTable {
		public ReadOnlyLuaTable(LuaValue table) {
			presize(table.length(), 0);
			for (Varargs n = table.next(LuaValue.NIL); !n.arg1().isnil(); n = table
					.next(n.arg1())) {
				LuaValue key = n.arg1();
				LuaValue value = n.arg(2);
				super.rawset(key, value.istable() ? new ReadOnlyLuaTable(value) : value);
			}
		}
		public LuaValue setmetatable(LuaValue metatable) { return error("table is read-only"); }
		public void set(int key, LuaValue value) { error("table is read-only"); }
		public void rawset(int key, LuaValue value) { error("table is read-only"); }
		public void rawset(LuaValue key, LuaValue value) { error("table is read-only"); }
		public LuaValue remove(int pos) { return error("table is read-only"); }
	}

	protected void initLua() {
		// Create server globals with just enough library support to compile user scripts.
		server_globals = new Globals();
		server_globals.load(new JseBaseLib());
		server_globals.load(new PackageLib());
		server_globals.load(new StringLib());

		user_globals = new Globals();
		user_globals.load(new JseBaseLib());
		user_globals.load(new PackageLib());
		user_globals.load(new Bit32Lib());
		user_globals.load(new TableLib());
		user_globals.load(new StringLib());
		user_globals.load(new JseMathLib());
		// To load scripts, we occasionally need a math library in addition to compiler support.
		// To limit scripts using the debug library, they must be closures, so we only install LuaC.
		server_globals.load(new JseMathLib());
		LoadState.install(server_globals);
		LuaC.install(server_globals);
		user_globals.set(LuaValue.valueOf("print"), new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				String outp = args.tojstring();
				if (!"".equals(outp)) {
					setOutput(getOutput() + " " + outp);
				}
				return LuaValue.varargsOf(new LuaValue[0]);
			}
		});
		// Set up the LuaString metatable to be read-only since it is shared across all scripts.
		LuaString.s_metatable = new ReadOnlyLuaTable(LuaString.s_metatable);
	}

	@Override
	protected void initCommands() {
		initLua();
		IRCBot.registerCommand("lua", "LuaJ sandbox");
		IRCBot.registerCommand("resetlua", "Resets the lua sandbox");
	}

	@Override
	public void handleCommand(String sender, MessageEvent event, String command, String[] args) {
		if (command.equals(Config.commandprefix + "lua") || command.equals(Config.commandprefix + "resetlua")) {
			chan = event.getChannel().getName();
		}
	}

	@Override
	public void handleCommand(String nick, GenericMessageEvent event, String command, String[] copyOfRange) {
		if (command.equals(Config.commandprefix + "lua")) {
			if (!event.getClass().getName().equals("org.pircbotx.hooks.events.MessageEvent")) {
				target = nick;
			} else {
				target = chan;
			}
			String message = "";
			for (String aCopyOfRange : copyOfRange)
			{
				message = message + " " + aCopyOfRange;
			}
			//Screw it I don't care, someone else can clean this up.
			Boolean iPrinted = false;
			String luaOut = Helper.ellipsize(runScriptInSandbox( message ), 400);
			if (getOutput().length() > 0) {
				IRCBot.getInstance().sendMessage(target , Helper.ellipsize(getOutput().replace("nil", "").trim(),400));
				setOutput("");
				iPrinted = true;
				//return;
			}

			if (luaOut.length() > 0 && iPrinted == false) 
				IRCBot.getInstance().sendMessage(target , luaOut);

		} else if (command.equals(Config.commandprefix + "resetlua")) {
			if (!event.getClass().getName().equals("org.pircbotx.hooks.events.MessageEvent")) {
				target = nick;
			} else {
				target = chan;
			}
			String message = "";
			for (String aCopyOfRange : copyOfRange)
			{
				message = message + " " + aCopyOfRange;
			}

			initLua();
			IRCBot.getInstance().sendMessage(target ,  "Sandbox reset");
		}
	}

	@Override
	public void handleMessage(String sender, MessageEvent event, String command, String[] args) {
		// TODO Auto-generated method stub

	}

	@Override
	public void handleMessage(String nick, GenericMessageEvent event, String command, String[] copyOfRange) {
		// TODO Auto-generated method stub

	}
}
