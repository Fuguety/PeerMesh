import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DnsService
{
    private final ConcurrentMap<String, PeerAddress> records;
    private final String cacheFile;



    public DnsService(String cacheFile)
    {
        this.records = new ConcurrentHashMap<String, PeerAddress>();
        this.cacheFile = cacheFile;
        load();
    }



    public void register(String username, String host, int port)
    {
        records.put(username, new PeerAddress(host, port));
        save();
    }



    public boolean merge(String username, String host, int port)
    {
        PeerAddress newAddress = new PeerAddress(host, port);
        PeerAddress oldAddress = records.put(username, newAddress);
        boolean changed = oldAddress == null || !oldAddress.toSocketString().equals(newAddress.toSocketString());

        if (changed)
        {
            save();
        }

        return changed;
    }



    public boolean mergeIfAddressUnknown(String username, String host, int port)
    {
        PeerAddress newAddress = new PeerAddress(host, port);

        for (String knownUser : records.keySet())
        {
            PeerAddress knownAddress = records.get(knownUser);

            if (knownAddress != null && knownAddress.toSocketString().equals(newAddress.toSocketString()) && !knownUser.equals(username))
            {
                return false;
            }
        }

        return merge(username, host, port);
    }



    public PeerAddress resolve(String username)
    {
        return records.get(username);
    }



    public Set<String> usernames()
    {
        return records.keySet();
    }



    public List<String> describeRecords()
    {
        List<String> lines = new ArrayList<String>();
        List<String> sortedUsers = new ArrayList<String>(records.keySet());
        Collections.sort(sortedUsers);

        for (String username : sortedUsers)
        {
            lines.add("[DNS] " + username + " -> " + records.get(username).toSocketString());
        }

        return lines;
    }



    public Map<String, PeerAddress> snapshot()
    {
        Map<String, PeerAddress> snapshot = new TreeMap<String, PeerAddress>();

        for (String username : records.keySet())
        {
            snapshot.put(username, records.get(username));
        }

        return snapshot;
    }



    private void load()
    {
        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(cacheFile))
        {
            properties.load(input);
        }
        catch (IOException exception)
        {
            return;
        }

        for (String username : properties.stringPropertyNames())
        {
            PeerAddress address = parseAddress(properties.getProperty(username));

            if (address != null)
            {
                records.put(username, address);
            }
        }
    }



    private void save()
    {
        Properties properties = new Properties();

        for (String username : records.keySet())
        {
            properties.setProperty(username, records.get(username).toSocketString());
        }

        try (FileOutputStream output = new FileOutputStream(cacheFile))
        {
            properties.store(output, "P2P chat DNS cache");
        }
        catch (IOException exception)
        {
            ConsolePrinter.warn("Could not save DNS cache");
        }
    }



    private PeerAddress parseAddress(String value)
    {
        if (value == null)
        {
            return null;
        }

        int separator = value.lastIndexOf(':');

        if (separator <= 0 || separator == value.length() - 1)
        {
            return null;
        }

        try
        {
            String host = value.substring(0, separator);
            int port = Integer.parseInt(value.substring(separator + 1));
            return new PeerAddress(host, port);
        }
        catch (NumberFormatException exception)
        {
            return null;
        }
    }
}
