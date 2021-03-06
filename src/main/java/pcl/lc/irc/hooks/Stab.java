/**
 * 
 */
package pcl.lc.irc.hooks;

import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.types.GenericMessageEvent;
import pcl.lc.irc.AbstractListener;
import pcl.lc.irc.Config;
import pcl.lc.irc.IRCBot;
import pcl.lc.utils.Helper;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

/**
 * @author Caitlyn
 *
 */
@SuppressWarnings("rawtypes")
public class Stab extends AbstractListener {

	@Override
	protected void initCommands() {
		IRCBot.registerCommand("stab", "Stab things with things");
	}

	public String dest;

	public String chan;
	public String target = null;
	@Override
	public void handleCommand(String sender, MessageEvent event, String command, String[] args) {
		if (command.equals(Config.commandprefix + "stab")) {
			chan = event.getChannel().getName();
		}
	}

	@SuppressWarnings("Duplicates")
	@Override
	public void handleCommand(String nick, GenericMessageEvent event, String command, String[] copyOfRange) {
		if (command.equals(Config.commandprefix + "stab")) {
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
			try
			{
				PreparedStatement statement = IRCBot.getInstance().getPreparedStatement("getRandomItem");
				ResultSet resultSet = statement.executeQuery();

				String item = "";
				Integer id = null;
				Integer uses = null;
				try
				{
					if (resultSet.next())
					{
						id = resultSet.getInt(1);
						item = resultSet.getString(2);
						uses = resultSet.getInt(3);
					}
				}
				catch (SQLException e)
				{
					e.printStackTrace();
				}

				String dust = "";
				if (uses != null && uses > 1)
				{
					statement = IRCBot.getInstance().getPreparedStatement("decrementUses");
					statement.setInt(1, id);
					statement.executeUpdate();
					System.out.println("Decrement uses for item " + id);
				}
				else if (uses != null)
				{
					statement = IRCBot.getInstance().getPreparedStatement("removeItemId");
					statement.setInt(1, id);
					statement.executeUpdate();
					System.out.println("Remove item " + id);
					dust = ", the " + item.replace("a ", "").replace("an ", "").replace("the ", "") + " crumbles to dust.";
				}

				ArrayList<String> actions = new ArrayList<>();
				actions.add("stabs");
				actions.add("hits");
				actions.add("slaps");

				int action = Helper.getRandomInt(0, actions.size() - 1);
				System.out.println("Action: " + action);

				String s = message.trim();
				if (!s.equals(IRCBot.ournick))
					IRCBot.getInstance().sendMessage(target ,  "\u0001ACTION " + actions.get(action) + " " + s + (!item.equals("") ? " with " : "") + item + dust + "\u0001");
				else
					IRCBot.getInstance().sendMessage(target ,  "\u0001ACTION uses " + (!item.equals("") ? item : " an orbital death ray") + " to vaporize " + Helper.antiPing(nick) + dust + "\u0001");
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
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
