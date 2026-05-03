import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

public class Main
{
    public static void main(String[] args)
    {
        if (args.length < 2)
        {
            printUsage();
            return;
        }

        String username = args[0];
        int port = Integer.parseInt(args[1]);
        boolean debugMode = true;
        String cacheFile = "dns-cache-" + username + ".properties";

        for (int index = 2; index < args.length; index++)
        {
            if ("--debug".equals(args[index]))
            {
                debugMode = true;
            }
            else if ("--no-debug".equals(args[index]))
            {
                debugMode = false;
            }
            else
            {
                cacheFile = args[index];
            }
        }

        try
        {
            ChatNode node = new ChatNode(username, port, cacheFile, debugMode);
            setTerminalTitle(username, port);
            node.start();
            runShell(node);
        }
        catch (Exception exception)
        {
            ConsolePrinter.error("Application could not start");
        }
    }



    private static void runShell(ChatNode node) throws Exception
    {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        printHelp();
        ConsolePrinter.activatePromptRedraw();

        while (true)
        {
            ConsolePrinter.prompt();
            String line = reader.readLine();

            if (line == null)
            {
                break;
            }

            ConsolePrinter.markInputSubmitted();

            if (!handleCommand(node, line.trim()))
            {
                break;
            }
        }

        node.stop();
    }



    private static void setTerminalTitle(String username, int port)
    {
        System.out.print("\033]0;P2P Node - " + username + " (" + port + ")\007");
        System.out.flush();
    }



    private static boolean handleCommand(ChatNode node, String line)
    {
        if (line.length() == 0)
        {
            return true;
        }

        String[] commandParts = line.split(" ", 2);
        String command = commandParts[0].toLowerCase();

        if ("register".equals(command))
        {
            handleRegister(node, line.split(" ", 4));
        }
        else if ("resolve".equals(command))
        {
            handleResolve(node, commandParts);
        }
        else if ("connect".equals(command))
        {
            handleConnect(node, commandParts);
        }
        else if ("send".equals(command))
        {
            handleSend(node, line.split(" ", 3));
        }
        else if ("search".equals(command))
        {
            handleSearch(node, commandParts);
        }
        else if ("benchmark".equals(command))
        {
            handleBenchmark(node, line.split(" ", 3));
        }
        else if ("heartbeat".equals(command))
        {
            handleHeartbeat(node, line.split(" ", 3));
        }
        else if ("whoami".equals(command) || "who".equals(command))
        {
            handleWhoAmI(node, line);
        }
        else if ("dns".equals(command))
        {
            printLines(node.dnsRecords());
        }
        else if ("peers".equals(command))
        {
            printLines(node.peerNames());
        }
        else if ("help".equals(command))
        {
            printHelp();
        }
        else if ("clear".equals(command) || "cls".equals(command))
        {
            ConsolePrinter.clear();
        }
        else if ("exit".equals(command) || "quit".equals(command))
        {
            return false;
        }
        else
        {
            if (line.length() > 1)
            {
                ConsolePrinter.error("Unknown command. Type help.");
            }
        }

        return true;
    }



    private static void handleRegister(ChatNode node, String[] parts)
    {
        if (parts.length < 4)
        {
            ConsolePrinter.error("Usage: register <username> <host> <port>");
            return;
        }

        try
        {
            node.register(parts[1], parts[2], Integer.parseInt(parts[3]));
            ConsolePrinter.info("Registered " + parts[1]);
        }
        catch (NumberFormatException exception)
        {
            ConsolePrinter.error("Port must be a number");
        }
    }



    private static void handleResolve(ChatNode node, String[] parts)
    {
        if (parts.length < 2)
        {
            ConsolePrinter.error("Usage: resolve <username>");
            return;
        }

        PeerAddress address = node.resolve(parts[1]);

        if (address == null)
        {
            ConsolePrinter.dns("User not found");
        }
        else
        {
            ConsolePrinter.dns(parts[1] + " -> " + address.toSocketString());
        }
    }



    private static void handleConnect(ChatNode node, String[] parts)
    {
        if (parts.length < 2)
        {
            ConsolePrinter.error("Usage: connect <username>");
            return;
        }

        if (node.connectToUser(parts[1]))
        {
            ConsolePrinter.info("Connected to " + parts[1]);
        }
    }



    private static void handleSend(ChatNode node, String[] parts)
    {
        if (parts.length < 3)
        {
            ConsolePrinter.error("Usage: send <username> <message>");
            return;
        }

        node.sendMessage(parts[1], parts[2]);
    }



    private static void handleSearch(ChatNode node, String[] parts)
    {
        if (parts.length < 2)
        {
            ConsolePrinter.error("Usage: search <username>");
            ConsolePrinter.error("Usage: search all");
            return;
        }

        if ("all".equalsIgnoreCase(parts[1]))
        {
            printLines(node.searchAllUsers());
            return;
        }

        PeerAddress address = node.searchUser(parts[1]);

        if (address == null)
        {
            ConsolePrinter.dns("User not found");
        }
        else
        {
            ConsolePrinter.dns(parts[1] + " -> " + address.toSocketString());
        }
    }



    private static void handleBenchmark(ChatNode node, String[] parts)
    {
        if (parts.length < 2)
        {
            ConsolePrinter.error("Usage: benchmark <username> [count]");
            return;
        }

        int count = 100;

        if (parts.length == 3)
        {
            try
            {
                count = Integer.parseInt(parts[2]);
            }
            catch (NumberFormatException exception)
            {
                ConsolePrinter.error("Benchmark count must be a number");
                return;
            }
        }

        node.runBenchmark(parts[1], count);
    }



    private static void handleHeartbeat(ChatNode node, String[] parts)
    {
        if (parts.length == 1 || "status".equalsIgnoreCase(parts[1]))
        {
            ConsolePrinter.line(node.heartbeatLogStatus());
            return;
        }

        if ("on".equalsIgnoreCase(parts[1]))
        {
            if (parts.length == 3)
            {
                setHeartbeatInterval(node, parts[2]);
                return;
            }

            node.setHeartbeatLogging(true);
            return;
        }

        if ("off".equalsIgnoreCase(parts[1]))
        {
            node.setHeartbeatLogging(false);
            return;
        }

        if ("every".equalsIgnoreCase(parts[1]))
        {
            handleHeartbeatEvery(node, parts);
            return;
        }

        ConsolePrinter.error("Usage: heartbeat [status|on [5-99]|off|every <5-99>]");
    }



    private static void handleWhoAmI(ChatNode node, String line)
    {
        String normalized = line.toLowerCase();

        if (!"whoami".equals(normalized) && !"who am i".equals(normalized))
        {
            ConsolePrinter.error("Usage: whoami");
            return;
        }

        ConsolePrinter.info("You are " + node.getUsername() + " on localhost:" + node.getPort());
    }



    private static void handleHeartbeatEvery(ChatNode node, String[] parts)
    {
        if (parts.length < 3)
        {
            ConsolePrinter.error("Usage: heartbeat every <5-99>");
            return;
        }

        setHeartbeatInterval(node, parts[2]);
    }



    private static void setHeartbeatInterval(ChatNode node, String value)
    {
        try
        {
            node.setHeartbeatLogInterval(Integer.parseInt(value));
        }
        catch (NumberFormatException exception)
        {
            ConsolePrinter.error("Heartbeat interval must be a number from 5 to 99");
        }
    }



    private static void printLines(List<String> lines)
    {
        if (lines.isEmpty())
        {
            ConsolePrinter.info("(none)");
            return;
        }

        for (String line : lines)
        {
            ConsolePrinter.line(line);
        }
    }



    private static void printHelp()
    {
        ConsolePrinter.line("Commands:");
        ConsolePrinter.line("  send <username> <message>");
        ConsolePrinter.line("  search <username>");
        ConsolePrinter.line("  search all");
        ConsolePrinter.line("  benchmark <username> [count]");
        ConsolePrinter.line("  heartbeat [status|on [5-99]|off|every <5-99>]");
        ConsolePrinter.line("  whoami/who am I");
        ConsolePrinter.line("  peers");
        ConsolePrinter.line("  dns");
        ConsolePrinter.line("  clear / cls");
        ConsolePrinter.line("  help");
        ConsolePrinter.line("  exit");
        ConsolePrinter.line("Optional:");
        ConsolePrinter.line("  register <username> <host> <port>");
        ConsolePrinter.line("  resolve <username>");
        ConsolePrinter.line("  connect <username>");
    }



    private static void printUsage()
    {
        ConsolePrinter.line("Usage: java -cp build Main <username> <port> [--debug|--no-debug] [dns-cache-file]");
    }
}
