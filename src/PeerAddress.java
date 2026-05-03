public class PeerAddress
{
    private final String host;
    private final int port;



    public PeerAddress(String host, int port)
    {
        this.host = host;
        this.port = port;
    }



    public String getHost()
    {
        return host;
    }



    public int getPort()
    {
        return port;
    }



    public String toSocketString()
    {
        return host + ":" + port;
    }



    @Override
    public String toString()
    {
        return toSocketString();
    }
}
