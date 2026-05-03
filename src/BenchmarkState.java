public class BenchmarkState
{
    private final String benchmarkId;
    private final int expectedMessages;
    private final long startTime;
    private int receivedAcks;
    private long totalLatency;



    public BenchmarkState(String benchmarkId, int expectedMessages)
    {
        this.benchmarkId = benchmarkId;
        this.expectedMessages = expectedMessages;
        this.startTime = System.currentTimeMillis();
        this.receivedAcks = 0;
        this.totalLatency = 0L;
    }



    public synchronized void recordLatency(long latency)
    {
        receivedAcks++;
        totalLatency += latency;
    }



    public synchronized boolean isComplete()
    {
        return receivedAcks >= expectedMessages;
    }



    public synchronized int getReceivedAcks()
    {
        return receivedAcks;
    }



    public int getExpectedMessages()
    {
        return expectedMessages;
    }



    public String getBenchmarkId()
    {
        return benchmarkId;
    }



    public synchronized long getAverageLatency()
    {
        if (receivedAcks == 0)
        {
            return 0L;
        }

        return totalLatency / receivedAcks;
    }



    public long getElapsedTime()
    {
        return System.currentTimeMillis() - startTime;
    }
}
