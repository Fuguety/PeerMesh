import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ConnectionManager
{
    private final ConcurrentMap<String, ConnectionHandler> sessions;



    public ConnectionManager()
    {
        this.sessions = new ConcurrentHashMap<String, ConnectionHandler>();
    }



    public boolean addSession(String username, ConnectionHandler handler)
    {
        ConnectionHandler existing = sessions.putIfAbsent(username, handler);
        return existing == null || existing == handler;
    }



    public ConnectionHandler getSession(String username)
    {
        return sessions.get(username);
    }



    public boolean hasSession(String username)
    {
        return sessions.containsKey(username);
    }



    public List<String> usernames()
    {
        return new ArrayList<String>(sessions.keySet());
    }



    public List<ConnectionHandler> handlers()
    {
        return new ArrayList<ConnectionHandler>(sessions.values());
    }



    public boolean removeSession(String username, ConnectionHandler handler)
    {
        return sessions.remove(username, handler);
    }



    public boolean isEmpty()
    {
        return sessions.isEmpty();
    }
}
