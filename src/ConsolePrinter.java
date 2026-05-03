public final class ConsolePrinter
{
    private static final int CLEAR_WIDTH = 160;
    private static boolean promptVisible = false;



    private ConsolePrinter()
    {
    }



    public static synchronized void line(String message)
    {
        clearInputLine();
        System.out.println(message);
        promptVisible = false;
    }



    public static synchronized void info(String message)
    {
        log("[INFO] " + message);
    }



    public static synchronized void error(String message)
    {
        log("[ERROR] " + message);
    }



    public static synchronized void warn(String message)
    {
        log("[WARN] " + message);
    }



    public static synchronized void secure(String message)
    {
        log("[SECURE] " + message);
    }



    public static synchronized void dns(String message)
    {
        log("[DNS] " + message);
    }



    public static synchronized void ping(String message)
    {
        log("[PING] " + message);
    }



    public static synchronized void pong(String message)
    {
        log("[PONG] " + message);
    }



    public static synchronized void incoming(String message)
    {
        log(message);
    }



    public static synchronized void prompt()
    {
        if (!promptVisible)
        {
            System.out.print("> ");
            System.out.flush();
            promptVisible = true;
        }
    }



    public static synchronized void markInputSubmitted()
    {
        promptVisible = false;
    }



    public static synchronized void activatePromptRedraw()
    {
    }



    public static synchronized void clear()
    {
        System.out.print("\033[H\033[2J");
        System.out.flush();
        promptVisible = false;
    }



    private static void log(String message)
    {
        clearInputLine();
        System.out.println(message);
        renderPrompt();
    }



    private static void clearInputLine()
    {
        System.out.print("\r");

        for (int index = 0; index < CLEAR_WIDTH; index++)
        {
            System.out.print(" ");
        }

        System.out.print("\r");
        System.out.flush();
        promptVisible = false;
    }



    private static void renderPrompt()
    {
        System.out.print("> ");
        System.out.flush();
        promptVisible = true;
    }
}
